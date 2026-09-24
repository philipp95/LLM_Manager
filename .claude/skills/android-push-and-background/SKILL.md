---
name: android-push-and-background
description: >
  Use when the app must keep working or notify the user while not in the
  foreground — FCM push for "agent finished" and "agent needs approval",
  notification channels and the POST_NOTIFICATIONS runtime permission,
  foreground services and the Android 14+ foregroundServiceType requirement,
  WorkManager for durable retry, Doze and battery-optimisation exemptions, and
  keeping a control WebSocket alive in the background. Triggers on FCM,
  FirebaseMessagingService, NotificationChannel, POST_NOTIFICATIONS,
  startForeground, ForegroundServiceStartNotAllowedException, WorkManager,
  Doze, setExactAndAllowWhileIdle, "notification doesn't arrive",
  or "the socket dies when I lock the phone".
---

# Background execution and push

The agents run for minutes to hours on the Beelink. The user will lock their phone.
Two requirements follow, and they have different answers:

1. **The user must learn when an agent finishes or blocks** → push (FCM).
2. **The app must not hold a socket open forever to find that out** → it shouldn't.

Getting this backwards — keeping a foreground service alive all day to watch a
WebSocket — drains the battery, gets the app killed, and still misses events.

## The rule: push wakes you, the socket does the work

```
agent blocks on approval
        │
        ▼
Beelink → FCM high-priority data message
        │
        ▼
phone wakes app → app connects WS → fetches state → shows notification
        │
        ▼
user approves → app sends → disconnects
```

Hold a live connection only while the user is looking at the app, or while a
foreground service with a visible notification is legitimately running. Everything
else is push-driven.

## Notification permission (Android 13+, API 33)

`POST_NOTIFICATIONS` is a **runtime permission**. Without it, notifications are
silently dropped — no error, no crash, nothing in logcat that points at the cause.

```kotlin
val launcher = registerForActivityResult(RequestPermission()) { granted -> /* … */ }

if (Build.VERSION.SDK_INT >= 33 &&
    checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
    launcher.launch(POST_NOTIFICATIONS)
}
```

Ask **in context**, not at first launch. The right moment is when the user starts
their first agent run: "notify you when this finishes?" is a request that explains
itself. A cold-start prompt gets denied, and a denial is effectively permanent —
after two dismissals Android stops showing the dialog entirely, and your only
remedy is deep-linking to system settings.

**Handle the denied case as a product state, not an error.** If notifications are
off, the app must say so on the screen where the user expects to be notified,
because otherwise they will simply never learn their agent finished.

## Channels — separate them by urgency

One channel means the user's only choice is all-or-nothing, and they will choose
nothing.

| Channel | Importance | For |
|---|---|---|
| `approvals` | `IMPORTANCE_HIGH` | agent blocked, needs a decision — heads-up, sound |
| `completions` | `IMPORTANCE_DEFAULT` | mission finished |
| `failures` | `IMPORTANCE_HIGH` | agent errored |
| `progress` | `IMPORTANCE_LOW` | ongoing run, silent, no badge |

Channel settings are **immutable after creation** — importance can only be lowered
by the user, never raised by you. Getting it wrong at launch means shipping a new
channel ID and migrating. Decide carefully once.

## FCM

Use **data messages**, not notification messages. A `notification` payload is
rendered by the system when the app is backgrounded and your code never runs — so
you cannot check whether the approval is still pending, cannot localise, and cannot
collapse duplicates.

Set **high priority** for approvals so the message pierces Doze. Normal priority
messages are deferred to a maintenance window, which for an approval request means
hours late and useless.

```kotlin
class AgentMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Runs in a ~10s window. Do not do network work here beyond enqueueing.
        val kind = message.data["kind"] ?: return
        when (kind) {
            "approval_required" -> notifyApproval(message.data)
            "mission_done"      -> WorkManager.getInstance(this).enqueue(syncWork())
        }
    }
    override fun onNewToken(token: String) {
        // Register with the Beelink. This fires on reinstall, restore, and rotation.
        WorkManager.getInstance(this).enqueue(registerTokenWork(token))
    }
}
```

Three non-obvious things:

- **`onNewToken` must be durable.** It fires at times you do not control, including
  when the app is not otherwise running. Enqueue a `WorkManager` job — do not fire a
  bare coroutine that dies with the process.
- **Payload limit is 4 KB.** Never push the agent's output. Push an ID; fetch the
  content. This is also the right security posture: the payload traverses Google's
  infrastructure.
- **Treat push as a hint, never as truth.** FCM does not guarantee delivery or
  order. The server remains authoritative; the app reconciles on connect. An
  approval that was already granted must not show a stale card.

## Foreground services (Android 14+, API 34)

If you genuinely need to stay connected — a long mission the user is actively
watching — a foreground service is the honest mechanism. Android 14 tightened it:

- `android:foregroundServiceType` is **required** in the manifest, and you must
  declare a matching typed permission (e.g. `FOREGROUND_SERVICE_DATA_SYNC`).
- You must have a **valid reason to start** while backgrounded, or you get
  `ForegroundServiceStartNotAllowedException`. Starting from an FCM high-priority
  message is allowed; starting from a timer is not.
- `dataSync` is capped — the system will stop long-running `dataSync` services.

```kotlin
ServiceCompat.startForeground(
    this, NOTIF_ID, notification,
    if (Build.VERSION.SDK_INT >= 29) FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
)
```

Use one **only while a mission is actively running**, with a notification showing
real progress and a **Stop** action. Stop it the moment the mission ends. A
foreground service with a vague "app is running" notification is what gets an app
uninstalled.

## WorkManager for everything durable

Token registration, state sync after a missed push, uploading queued approvals:

```kotlin
val work = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()

WorkManager.getInstance(context)
    .enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, work)
```

- **Use unique work** with `KEEP` or `APPEND_OR_REPLACE`. Without it, ten pushes
  enqueue ten syncs.
- **Workers must be idempotent.** They will run twice. Design for it rather than
  hoping.
- **WorkManager is not for the control channel.** Minimum periodic interval is 15
  minutes and the system reschedules freely — it is for durable background chores,
  not for anything the user is waiting on.

## Doze, App Standby, and OEM battery killers

- Doze defers network and jobs in maintenance windows. High-priority FCM pierces it;
  nothing else reliably does.
- **Do not reach for `SCHEDULE_EXACT_ALARM`.** It is heavily restricted, requires
  user grant on Android 14+, and Play review scrutinises it. There is no alarm-shaped
  problem here — push is the mechanism.
- OEM skins (Xiaomi, Oppo, Samsung, Huawei) kill background apps far more
  aggressively than stock. Offer a one-tap route to
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` when the user reports missed
  notifications, and **detect the state** with `isIgnoringBatteryOptimizations` so
  the app can explain the problem rather than appear broken.
- The same applies to the VPN client — see `remote-server-connectivity`. A killed
  Tailscale is indistinguishable from a dead server unless you check.

## Testing

- `adb shell cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` — simulate an OEM
  killing background work.
- `adb shell dumpsys deviceidle force-idle` — force Doze and verify approvals still
  land.
- `adb shell am kill <pkg>` while a mission runs — verify the app recovers state
  from the server, not from memory.
- Revoke `POST_NOTIFICATIONS` in settings and confirm the app surfaces it instead of
  silently failing.

## Checklist

- [ ] Push wakes the app; no all-day socket
- [ ] `POST_NOTIFICATIONS` requested in context, denial handled as a visible state
- [ ] Separate channels by urgency; importance decided before first release
- [ ] FCM **data** messages, high priority for approvals
- [ ] Payload carries an ID, never agent output; under 4 KB
- [ ] `onNewToken` enqueues durable work
- [ ] Push treated as a hint; server reconciliation on connect
- [ ] `foregroundServiceType` declared; service scoped to an active mission, with Stop
- [ ] Unique, idempotent WorkManager jobs with backoff
- [ ] Battery-optimisation state detected and explained, not silently endured

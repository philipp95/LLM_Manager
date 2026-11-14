// Run with node scripts/dashboard-ui.test.cjs. No browser or dependencies required.
const { readFileSync } = require('node:fs');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const html = readFileSync('server/api/src/main/resources/dashboard/index.html', 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(source);
function load(name) {
  const start = source.indexOf('  function ' + name + '(');
  assert.ok(start >= 0, name + ' exists');
  const end = source.indexOf('\n  }', start) + 4;
  return source.slice(start, end);
}
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(load('branchProgress'), sandbox);
function progress(pub) { return sandbox.branchProgress({ publish: pub }); }
assert.equal(progress(null).label, 'Local branch');
assert.equal(progress({ pushed: false }).step, 0);
assert.equal(progress({ pushed: true, upToDate: false }).label, 'Push update');
assert.equal(progress({ pushed: true, upToDate: true }).step, 1);
assert.equal(progress({ pullRequest: { state: 'open' } }).step, 2);
assert.equal(progress({ pullRequest: { state: 'active' } }).step, 2);
assert.equal(progress({ pullRequest: { state: 'merged' } }).step, 3);
assert.equal(progress({ pullRequest: { state: 'completed' } }).step, 3);
assert.equal(progress({ pullRequest: { state: 'closed' } }).label, 'PR closed');
assert.equal(progress({ pullRequest: { state: 'abandoned' } }).label, 'PR closed');
const routePattern = source.match(/const match = (\/.*\/)[.]exec\(hash\)/)[1];
const route = vm.runInNewContext(routePattern);
assert.ok(route.test('repo/demo/issues'), 'issues has a repository route');
assert.ok(route.test('repo/demo/branches'), 'branch board keeps its route');
console.log('Dashboard workflow checks passed');

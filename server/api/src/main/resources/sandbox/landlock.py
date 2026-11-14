#!/usr/bin/env python3
"""Runs a command confined by Landlock, the Linux kernel's unprivileged sandbox.

Usage: landlock.py [--rw PATH]... [--ro PATH]... -- COMMAND [ARG]...

The command and everything it starts may write only below the --rw paths and read
only below the --rw and --ro paths. Running programs and walking directories are
not restricted. It also cannot open Unix sockets (a seccomp filter), because
Landlock does not cover them and tmux, D-Bus, systemd and Docker each offer a
socket that would start commands outside the confinement; and, where the kernel
supports it, it cannot signal processes outside. Needs no root, no user
namespaces and no extra packages, so it works where bubblewrap cannot. Exit code
96 means the sandbox could not be set up; the command is never run unconfined.

Generated and used by LLM Manager; see docs/security.md.
"""
import ctypes
import os
import struct
import sys

FAILED = 96

# linux/landlock.h
CREATE_RULESET_VERSION = 1
RULE_PATH_BENEATH = 1
EXECUTE, WRITE_FILE, READ_FILE, READ_DIR = 1 << 0, 1 << 1, 1 << 2, 1 << 3
REMOVE_DIR, REMOVE_FILE, MAKE_CHAR, MAKE_DIR = 1 << 4, 1 << 5, 1 << 6, 1 << 7
MAKE_REG, MAKE_SOCK, MAKE_FIFO, MAKE_BLOCK, MAKE_SYM = 1 << 8, 1 << 9, 1 << 10, 1 << 11, 1 << 12
REFER, TRUNCATE = 1 << 13, 1 << 14

READ = READ_FILE | READ_DIR
WRITE_V1 = WRITE_FILE | REMOVE_DIR | REMOVE_FILE | MAKE_CHAR | MAKE_DIR | MAKE_REG | MAKE_SOCK | MAKE_FIFO | MAKE_BLOCK | MAKE_SYM
# Rights that make sense on a file that is not a directory.
ON_FILES = WRITE_FILE | READ_FILE | TRUNCATE

SCOPE_ABSTRACT_UNIX_SOCKET, SCOPE_SIGNAL = 1 << 0, 1 << 1

SYS_CREATE_RULESET, SYS_ADD_RULE, SYS_RESTRICT_SELF = 444, 445, 446
PR_SET_NO_NEW_PRIVS, PR_SET_SECCOMP, SECCOMP_MODE_FILTER = 38, 22, 2

# machine -> (AUDIT_ARCH_*, the number of the socket system call)
MACHINES = {"x86_64": (0xC000003E, 41), "aarch64": (0xC00000B7, 198)}
AF_UNIX, EACCES = 1, 13
SECCOMP_ALLOW, SECCOMP_ERRNO = 0x7FFF0000, 0x00050000
LOAD, JUMP_EQUAL, JUMP_AT_LEAST, RETURN = 0x20, 0x15, 0x35, 0x06


class SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]


def fail(message):
    sys.stderr.write("landlock: " + message + "\n")
    sys.exit(FAILED)


def parse(argv):
    writable, readable, i = [], [], 0
    while i < len(argv) and argv[i] != "--":
        if argv[i] not in ("--rw", "--ro") or i + 1 >= len(argv):
            fail("usage: landlock.py [--rw PATH]... [--ro PATH]... -- COMMAND [ARG]...")
        (writable if argv[i] == "--rw" else readable).append(argv[i + 1])
        i += 2
    command = argv[i + 1:]
    if not command:
        fail("no command given")
    return writable, readable, command


def deny_unix_sockets(libc):
    """socket(AF_UNIX, ...) fails with EACCES; socketpair, which programs use for their own children, still works."""
    machine = MACHINES.get(os.uname().machine)
    if machine is None:
        fail("no Unix socket filter for " + os.uname().machine)
    arch, socket_call = machine
    steps = [
        (LOAD, 0, 0, 4),                    # the architecture of the call
        (JUMP_EQUAL, 0, 6, arch),           # another one (32-bit calls): refuse
        (LOAD, 0, 0, 0),                    # the system call number
        (JUMP_AT_LEAST, 4, 0, 0x40000000),  # the x32 variants: refuse
        (JUMP_EQUAL, 0, 2, socket_call),    # anything but socket(): allow
        (LOAD, 0, 0, 16),                   # its first argument, the address family
        (JUMP_EQUAL, 1, 0, AF_UNIX),
        (RETURN, 0, 0, SECCOMP_ALLOW),
        (RETURN, 0, 0, SECCOMP_ERRNO | EACCES),
    ]
    code = ctypes.create_string_buffer(b"".join(struct.pack("=HBBI", *step) for step in steps))
    program = SockFprog(len(steps), ctypes.addressof(code))
    if libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ctypes.byref(program), 0, 0) != 0:
        fail("could not install the Unix socket filter: " + os.strerror(ctypes.get_errno()))


def main():
    writable, readable, command = parse(sys.argv[1:])
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    abi = libc.syscall(SYS_CREATE_RULESET, None, ctypes.c_size_t(0), ctypes.c_uint32(CREATE_RULESET_VERSION))
    if abi < 1:
        fail("this kernel does not offer Landlock")
    write = WRITE_V1 | (REFER if abi >= 2 else 0) | (TRUNCATE if abi >= 3 else 0)
    handled = write | READ

    # struct landlock_ruleset_attr: file access, network access (not handled), and from version 6 scopes.
    scoped = abi >= 6
    attr = ctypes.create_string_buffer(struct.pack("=QQQ", handled, 0, SCOPE_ABSTRACT_UNIX_SOCKET | SCOPE_SIGNAL if scoped else 0))
    ruleset = libc.syscall(SYS_CREATE_RULESET, attr, ctypes.c_size_t(24 if scoped else 8), ctypes.c_uint32(0))
    if ruleset < 0:
        fail("could not create the ruleset: " + os.strerror(ctypes.get_errno()))

    def allow(path, access):
        try:
            fd = os.open(path, os.O_PATH | os.O_CLOEXEC)
        except OSError:
            return  # A path that is not there needs no rule.
        try:
            if not os.path.isdir(path):
                access &= ON_FILES
            # struct landlock_path_beneath_attr is packed: 64 bits of access, then the descriptor.
            rule = ctypes.create_string_buffer(struct.pack("=Qi", access, fd))
            if libc.syscall(SYS_ADD_RULE, ctypes.c_int(ruleset), ctypes.c_int(RULE_PATH_BENEATH), rule, ctypes.c_uint32(0)) != 0:
                fail("could not allow " + path + ": " + os.strerror(ctypes.get_errno()))
        finally:
            os.close(fd)

    for path in writable:
        allow(path, handled)
    for path in readable:
        allow(path, READ)

    if libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
        fail("could not set no_new_privs")
    if libc.syscall(SYS_RESTRICT_SELF, ctypes.c_int(ruleset), ctypes.c_uint32(0)) != 0:
        fail("could not apply the ruleset: " + os.strerror(ctypes.get_errno()))
    os.close(ruleset)
    deny_unix_sockets(libc)
    try:
        os.execvp(command[0], command)
    except OSError as error:
        fail("could not run " + command[0] + ": " + str(error))


if __name__ == "__main__":
    main()

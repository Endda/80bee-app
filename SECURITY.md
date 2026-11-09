# Security Policy

80bee can run ADB and fastboot operations on Android devices, so security bugs here can affect real phones. Reports are welcome and appreciated.

## Security maintainer

Doug Lynch ([Endda](https://github.com/Endda)) is the creator, sole maintainer, and security maintainer of this project.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through GitHub: [open a private security advisory](https://github.com/Endda/80bee-app/security/advisories/new).

Helpful details include:

- What the problem is and what an attacker could do with it
- Steps to reproduce it
- The app version, Android version, and device involved
- A suggested fix, if you have one

## What to expect

I am one person, so these are goals and not guarantees. I aim to acknowledge reports within 7 days and to keep you updated while I work on a fix. Once a fix is released, I will publish an advisory and credit you unless you prefer to stay anonymous.

Please give me a reasonable chance to release a fix before sharing details publicly.

## Scope

In scope:

- The 80bee Android app in this repository
- The `adblib` code included in this repository, as used by the app
- ADB and fastboot command handling, USB and OTG connections, sideloading, and device authorization
- The 80bee.com website and its WebUSB tools, which can also be reported through the link above

Out of scope:

- Testing against devices, accounts, or data that are not yours
- Denial-of-service testing against 80bee.com
- Social engineering
- Issues that require an attacker to already have full control of an unlocked device

## Supported versions

Security fixes go into the latest release. Please update and confirm the problem still exists before reporting it.

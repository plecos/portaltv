# Contributing

Thanks for wanting to help. Portal is source-available under the PolyForm Noncommercial License
(see `LICENSE`), and its author also offers commercial licenses.

## Your contribution and the license

To keep that possible, every contribution needs one thing from you. By opening a pull request you
confirm that:

1. You wrote the change, or have the right to submit it.
2. You grant the Portal TV project a perpetual, worldwide, royalty-free license to use, modify, distribute and
   sublicense your contribution, including in commercial licenses of Portal.
3. You keep the copyright in your contribution.

Add this line to each commit message (`git commit -s` does it for you):

```
Signed-off-by: Your Name <you@example.com>
```

## Making a change

- Open an issue first for anything big, so you don't build something that won't be merged.
- Build and test as described in the README. A pull request must pass `./gradlew testDebugUnitTest lintDebug`; CI runs the same.
- Keep changes small and focused, add a unit test when you can, and match the surrounding code.
- Don't commit keys, passwords, `local.properties` or video files that aren't yours to share.

## Security problems

Don't open a public issue. See `SECURITY.md`.

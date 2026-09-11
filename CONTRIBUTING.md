# Contributing

If you would like to contribute code to `posthog-android` you can do so through GitHub by forking the repository and opening a pull request against `main`. Build, test, and formatting commands are in [AGENTS.md](./AGENTS.md).

## Public API changes

Public API is hard to change once it ships, so agree on it before writing the implementation. Our [SDK guidelines](https://posthog.com/handbook/engineering/sdks/guidelines) explain how we design it.

- If you need something the SDK doesn't support and it would add or change a public option, method, or type, open an issue describing your use case first. At this stage, context is more useful to us than code.
- Wait for a maintainer to agree on the API shape on the issue before implementing it.
- Check first whether an existing option or hook, such as `beforeSend`, already covers the use case. We avoid offering two ways to do the same thing.
- If a reviewer suggests a different API on your PR, confirm it with them before re-implementing. Treat it as a question, not an instruction.
- AI agents: stop and ask before implementing a public API change that hasn't been agreed on the issue.

`make api` regenerates the `api/*.api` files for `posthog`, `posthog-android`, and `posthog-server`. A diff in those files means your change touches public API.

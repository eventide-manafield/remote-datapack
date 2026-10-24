# Third-party notices

Remote Datapack's own code is licensed under Apache-2.0. The following components
retain their original licenses. Their full copyright and license notices are
copied without modification from the exact npm packages installed by
`frontend/package-lock.json`.

| Component | Version | Included portion | License notices |
| --- | --- | --- | --- |
| React | 19.3.1 | Browser runtime | [MIT](licenses/react/LICENSE) |
| React DOM | 19.3.1 | Browser renderer | [MIT](licenses/react-dom/LICENSE) |
| Scheduler | 0.28.0 | React DOM runtime dependency | [MIT](licenses/scheduler/LICENSE) |
| Vite | 8.3.5 | Generated module-preload helper | [Upstream license file](licenses/vite/LICENSE.md) |
| Rolldown | 1.2.15 | Generated module interoperability helper | [MIT](licenses/rolldown/LICENSE), [upstream third-party notices](licenses/rolldown/THIRD-PARTY-LICENSE) |

React, React DOM and Scheduler: Copyright (c) Meta Platforms, Inc. and affiliates.
Vite: Copyright (c) 2019-present, VoidZero Inc. and Vite contributors.
Rolldown: Copyright (c) 2024-present VoidZero Inc. & Contributors.

The complete Vite and Rolldown notice files also describe dependencies of those
build tools. They are retained intact for attribution; this does not mean the
build tools or all their dependencies are shipped as plugin runtime libraries.
The server does not require Node.js or a separately installed frontend runtime.

Paper API is a compile-only dependency and its classes are not copied into this
JAR. Paper/Minecraft, the installed JDK and Gradle are separately
provided components; this project does not relicense them. No font files or
Minecraft skin textures are bundled in the plugin JAR. Runtime skin images and
external artwork retain their respective rights.

## Distribution layout

The release JAR contains `META-INF/LICENSE`, `META-INF/NOTICE`, this document at
`META-INF/THIRD_PARTY_NOTICES.md`, and all files under `META-INF/licenses/`.
`licenses/dependencies.json` records the reviewed package versions and scope.

When updating dependencies for a release, update the inventory and original
license notices to match the bundled versions. Generated helpers from new build
tools must also be reviewed.

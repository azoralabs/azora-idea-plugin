<p align="center">
  <img src="src/main/resources/icons/azora_logo.png" alt="Azora Logo" width="128" />
</p>

<h1 align="center">Azora Language Plugin for JetBrains IDEs</h1>

<p align="center">
  Official IDE plugin bringing full <a href="https://azoralang.org">Azora</a> language support to
  <a href="https://www.jetbrains.com/idea/">IntelliJ IDEA</a> and
  <a href="https://developer.android.com/studio">Android Studio</a>.
</p>

<p align="center">
  <a href="https://azoralang.org">Website</a> &middot;
  <a href="https://docs.azoralang.org">Docs</a> &middot;
  <a href="https://book.azoralang.org">Book</a> &middot;
  <a href="https://code.azoralang.org">Playground</a>
</p>

---

## Features

### Highlighting

- **Azora's own palette**, shared with the [playground](https://code.azoralang.org): bold keywords, purple macros,
  teal types, colored string interpolation and escapes, wavy diagnostics. Every category has a light and a dark
  default and is overridable under **Editor | Color Scheme | Azora**.
- **Macros are discovered, not hardcoded.** A name is a macro because some `meta` declaration in your project, the
  SDK, or a dependency says so. That is what lets a *keyword-named* macro work correctly: `with` is a keyword in
  `with (ctx) { … }` and a macro in `Query<Position with Velocity>`, because `azora-engine` declares
  `$Base with $Filter` in its `meta type` block.
- **Smart casts** are highlighted for the extent of the narrowing (`if x is T`, `guard … is T`, `when` arms).
- **Inferred-type inlay hints**, off by default, under **Settings | Editor | Inlay Hints | Azora**.

### Code intelligence

- **Completion** that follows context: real members after `.` and `::`, real module paths in imports, a type's
  fields inside its constructor call, the macros your project declares, and keywords only once you start a word.
- **Live templates** for every declaration form in the 0.0.4 grammar (`func`, `pack`, `spec`, `meta`, `test`, …).
- **Go-to-declaration** and **quick documentation** that scope names the way the language does — locals and
  parameters before file declarations, members resolved against their receiver's type, imports before the rest of
  the project. Declarations in the SDK and in path dependencies are navigable.
- **Diagnostics** underlined on the exact text at fault, with quick fixes for module typos, unknown escapes,
  unterminated strings and stray brackets.
- **Structure view**, **find usages**, **folding**, **brace matching**, **commenting**, and **indentation**.

### Projects and running

- **`.azon` support**: syntax highlighting and validation for AZON manifests and data files, including checks that
  declared targets exist and that dependency paths resolve.
- **Project templates**: executable, library, and multi-module workspace, each scaffolded with AZON manifests,
  sources, and a test block.
- **Run gutter icons** on `func main`, `task main`, and every `test` block, plus one run configuration per target
  declared in your manifests.

## Requirements

- [IntelliJ IDEA](https://www.jetbrains.com/idea/) 2025.3+ or [Android Studio](https://developer.android.com/studio) Meerkat+
- [Azora SDK](https://azoralang.org) installed (for run configurations)
- Azora language 0.0.4

## Installation

### From source

```bash
git clone https://github.com/azora-tech/azora-idea-plugin.git
cd azora-idea-plugin
./gradlew build
```

The built plugin zip will be in `build/distributions/`. Install it via **Settings > Plugins > Install Plugin from Disk**.

## Getting Started

1. Install the plugin
2. **File > New > Azora Project** (or use the New Project wizard)
3. Choose **Executable**, **Library**, or **Multi-module workspace**
4. Set your Azora SDK path (e.g. `~/.azoralang`)
5. Start coding in `.az` files

Run configurations are generated from the targets your manifest declares. Editing a manifest shows a banner
offering to re-read it.

## Project structure

A single package:

```
my-project/
  package.azon         # name, version, kind, entry, targets, dependencies
  src/
    main.az            # entry point
```

A workspace:

```
my-workspace/
  workspace.azon       # members
  packages/
    my-core/
      package.azon     # kind: "lib"
      src/core.az
    my-app/
      package.azon     # kind: "exe", depends on ../my-core
      src/main.az
```

`dependencies` is what tells the IDE where a package's sources are, so its types, functions, and macros resolve
across the workspace:

```azon
dependencies: {
    my-core: { path: "../my-core" }
}
```

Legacy `azora.toml` projects are still read.

## Links

| Resource | URL |
|----------|-----|
| Azora Website | [azoralang.org](https://azoralang.org) |
| Documentation | [docs.azoralang.org](https://docs.azoralang.org) |
| The Azora Book | [book.azoralang.org](https://book.azoralang.org) |
| Source Code | [code.azoralang.org](https://code.azoralang.org) |
| IntelliJ IDEA | [jetbrains.com/idea](https://www.jetbrains.com/idea/) |
| Android Studio | [developer.android.com/studio](https://developer.android.com/studio) |

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 AzoraLabs.

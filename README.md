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
  default and is overridable under **Editor | Color Scheme | Azora**. Realm-qualified paths such as `ide::editor`
  are styled as complete paths, not just their first segment.
- **Macros are discovered, not hardcoded.** A name is a macro because a `macro @name`
  declaration in your project, the SDK, or a dependency says so. That is what lets a *keyword-named* macro work
  correctly: `with` remains a keyword while `@with` is a macro in
  `@query [Position!, Velocity&] @with Render @without Disabled`.
- **Smart casts** are highlighted for the extent of the narrowing (`if x is T`, `guard … is T`, `when` arms).
- **Inferred-type inlay hints**, off by default, under **Settings | Editor | Inlay Hints | Azora**.

### Code intelligence

- **Completion** that follows context: real members after `.` and `::`, real module paths in imports, a type's
  fields inside its constructor call, the macros your project declares, and keywords only once you start a word.
- **Live templates** for current declaration forms (`func`, `react func`, `react ctor`, `pack`, `spec`, `annot`, `macro`, `test`, …).
- **Go-to-declaration** and **quick documentation** resolve symbols by use-site context — locals and parameters before
  file declarations, members against their receiver's type, and types/callables by their role rather than by spelling.
  Declarations in the SDK and in path dependencies are navigable.
- **Diagnostics** underlined on the exact text at fault, with unused-local warnings and quick fixes for naming,
  constructor/enum shorthand, numeric literals or casts, module/annotation typos, unknown escapes,
  unterminated strings and stray brackets.
- **Structure view**, **find usages**, **folding**, **brace matching**, **commenting**, and **indentation**.

### Projects and running

- **`.azon` support**: syntax highlighting, validation, automatic indentation, code-style settings, and Reformat Code for AZON manifests and data files, including checks that
  declared targets exist and that dependency paths resolve.
- **Project templates**: executable, library, and multi-module workspace, each scaffolded with AZON manifests,
  sources, and a test block.
- **Run gutter icons** on `func main` (including `async`/`react` forms) and every `test` block, plus one run configuration per target
  declared in your manifests.

## Requirements

- [IntelliJ IDEA](https://www.jetbrains.com/idea/) 2025.3+ or [Android Studio](https://developer.android.com/studio) Meerkat+
- [Azora SDK](https://azoralang.org) installed (for run configurations)
- Azora language 0.0.5

## Release notes

### 0.0.7

- Synchronized all compiler keywords and contextual words, including `assoc`, `derives`, and `without`; `reflect` is correctly treated as a function.
- Corrected context colors: yellow annotations, orange generics, blue loop labels, ordinary function text, and complete italic realm paths.
- Rebuilt hover and go-to-declaration around actual symbols and use roles, including lexical shadowing, members, exact offsets, stdlib, and dependencies.
- Fixed exact-prefix completion (`Anchor` no longer becomes `TilemapAnchor`) and current annotation/macro completion.
- Added unused-local and clean-code warnings with automatic fixes for naming, shorthand expressions, numeric literals/casts, imports, annotations, strings, and brackets.
- Added automatic indentation, code-style settings, and Reformat Code for nested comma-free `.azon` objects and arrays; updated Azora indentation, structure view, project templates, run markers, snippets, and live templates to current syntax.

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

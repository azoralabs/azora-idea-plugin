# Changelog

## 0.0.9

- Colored decimal, hexadecimal, and binary integer literals with a balanced pastel-cyan palette in both light and dark themes; real literals keep their existing foreground.
- Replaced the plugin's local semantic analyzer with a project-scoped AZLS process using standard JSON-RPC/LSP framing.
- Added versioned document synchronization, precise compiler-owned diagnostics, related locations, and version-checked code actions.
- Routed completion, hover, definition, and semantic highlighting through AZLS.
- Added the dedicated teal `associatedType` semantic token for `assoc Item` declarations and uses.
- Added bounded language-server restart, orderly shutdown, stderr logging, and document close notifications.

## 0.0.8

### Two new views

- The IR beside the source. An `.az` file opens as an editor with a preview, the way a Markdown file does, showing what the compiler makes of it in two tabs: **IR** and **Optimized IR**. It refreshes shortly after typing stops, from the buffer as it stands rather than its last save, and shows only *this file's* declarations - a full dump is mostly the standard library the file reached. The compiler is asked rather than imitated, so the pane never disagrees with a build. Both panes are editors colored as Azora: the IR is Azora, and reads like it.
- Doc comments render in place. A `/** … */` is drawn as formatted text and turns back into source the moment the caret enters it (*Editor | General | Appearance | Render documentation comments*). The licence header at the top of a file now folds by default, as it does in every other language.

### Imports

- One reader for the whole import grammar, so completion, navigation, Optimize Imports and highlighting agree. A group written across several lines, or with a comment in it, is now read correctly; before, everything it selected was uncolored and unnavigable.
- **Optimize Imports** narrows `import path::*` to what the file actually uses. It never touches an explicit import, and leaves a wildcard alone when the index knows nothing about its module - silently dropping a dependency is the failure nobody notices.
- Completion offers names that are not imported yet, tagged with the module that declares them, and writes the import in the same keystroke. Alt-Enter does the same for a name already written.
- An import that is written for you names what it brings in: `import std.traits::PartialEqual`. A second name out of the same module joins the clause that is already there - `import std.traits::[PartialEqual, Equal]` - rather than opening a second clause about one module.
- A `::` clause reaches the names it selects and no others, so `Equal` beside an imported `PartialEqual` is still reported until it has been asked for too.
- The first import of a file is written with the blank line above and below it that the rest of the block has.
- A variant is reached through its type, so `Compare.Equal` no longer counts as having the name `Equal` in scope - which is what hid the spec of that name.
- Go-to-definition works on the segments of an import path.

### Errors for undefined symbols

- Every undeclared name is now reported, not only the capitalised ones and the ones applied to `(`. The check stays quiet because the editor learned the forms that bind without being declarations: a loop's row (`for row in rows`, `for [head, tail] in rows`, `inline for name in … with index`), a pattern's capture (`.Bool(v) ->`), a lambda's parameters, a scope's name, and `catch`/`rescue` bindings. Validated against the whole standard library.
- A name that exists but is not imported here is reported separately, with the import as the fix.

### New rules the compiler enforces

- A `prop` only observes: `[self&]` is the only receiver it takes. `[self!]` says that reading it writes through what it was read from, and `[self]` says that reading it ends the value - both are what a `func` is for.
- A width suffix is not part of a literal. `4L` is reported with both ways out: drop it, or name the width as `Long(4)`.

### Diagnostics and quick fixes

- Borrowing: writing through a `[self&]` is an error with the exclusive borrow as its fix; a `[self!]` that never writes is a weak warning with the shared one; a receiver nothing reaches is reported; and inside an `impl` the receiver's type is always `Self`, so writing it is reported as saying nothing.
- Style, each with a fix: `i += 1` becomes `i++`; a run of `purge` statements becomes one `purge [a, b, c]`; a one-line `func`, `prop` or block body moves onto its own line; `[a: Int, b: Int]` becomes `[a, b]: Int`; a repeated initializer `= [0, 0, 0]` becomes `= 0`; a `when` arm holding one statement drops its braces; a constructor that only restates the defaults is reported as redundant; a property that is one expression is offered in its short form.
- Unused locals, parameters and members are reported (never dimmed - a color says what a name *is*, and whether anyone calls it is a different question).
- A declaration with no body - a `spec` member, a `bridge func` - no longer adopts the next declaration's block as its own. Everything in that block answered to the wrong owner, which is what made `@Supress(.Unused)` on a module header miss the enum cases and spec members below it.

### Colours

- There are no contextual keywords. The compiler's lexer answers `keywords[text] ?: IDENTIFIER` and nothing else, so `derives`, `where`, `assoc`, `requires`, `binds`, `includes`, `module`, `union`, `async`, `escaping`, `lend` and `seal` are keywords wherever they are written - `pack Point derives Equal derives Hash derives Display` colors each clause consistently, and neither depends on the editor recognising the shape of the header. The one allowance is the parser's own: the thirteen keywords `consumeIdentifierLike` accepts as a name (`take`, `union`, `error`, `test`, `prop`, …) still read as names after a `.` or a declaration head.
- `__int`, `__uint` and `__float` read as the keywords they are, not as types: no `.az` source declares them, and nothing else may be spelled with their `__`.
- `prop` names read as any other name - the ordinary foreground, italic and underlined - rather than a brighter white than the fields beside them.
- Parameters are a light gray-blue; generic type parameters take the macro color; error cases are redder; a `${…}` macro hole is gold including its braces.
- A `@Deprecated` declaration is struck through wherever its name appears, over whatever color it already had.
- Doc comments have their own green, split into prose, `@tag`, and the name a tag documents.

### Fixes

- Doc comments no longer lose their color while a file is edited: the lexer had reported every token boundary as a safe place to restart, and the editor took it at its word after an edit.
- Colours update as soon as a declaration in another open tab changes, rather than waiting for a reparse.
- `derives` keeps its keyword color after a pack that states which literal it is written as, and when the clause opens its own line.
- `where` keeps its keyword color after a signature with a callable parameter - the `>` of `->` was being counted as a closing angle bracket.
- A quick fix is no longer built from the text with literals blanked out, which is what once rewrote `text = text + "0"` into `text = + text`.
- The Kotlin build daemon gets 3 GB, so a full build no longer fails as "Backend Internal error", which reads like a code fault and is not one.

## 0.0.7

- Synchronized every reserved and contextual word with the compiler. This includes contextual `assoc` and `derives`, reserved `without`, and removal of `reflect` from keyword handling because it is a standard-library function.
- Corrected semantic colors by context: annotations are yellow, generic parameters orange, loop labels blue, functions use the ordinary foreground, and every segment of a realm path such as `ide::editor` is italic.
- Reworked go-to-declaration and hover around symbol identity and use-site role instead of spelling alone. Lexical scopes, shadowing, receiver members, types, callables, exact local/parameter offsets, documentation, stdlib sources, and path dependencies are resolved.
- Fixed exact-prefix completion, including the `Anchor`/`TilemapAnchor` suffix collision, and made annotation and macro completion follow current `@Name`/`@name` syntax.
- Added precise unused-local warnings and quick fixes for lower/UpperCamelCase naming, illegal underscore forms, constructor and enum shorthand, floating-point literals or explicit casts, import typos, annotation typos, strings, and brackets.
- Updated `.az` indentation and added complete `.azon` Enter-key indentation, code-style settings, and Reformat Code support for nested comma-free objects and arrays. Structure view, run markers, project scaffolds, folding, snippets, and live templates now use current syntax: `react func`, `react ctor`, `async func`, current receiver parameters, exact ECS query syntax, and comma-free pack/enum members.
- Added regressions against real compiler and engine sources for contextual coloring, string interpolation references, C bridge names, current macros, external symbols, and same-spelled symbols with different meanings.

# Changelog

## 0.0.7

- Synchronized every reserved and contextual word with the compiler. This includes contextual `assoc` and `derives`, reserved `without`, and removal of `reflect` from keyword handling because it is a standard-library function.
- Corrected semantic colors by context: annotations are yellow, generic parameters orange, loop labels blue, functions use the ordinary foreground, and every segment of a realm path such as `ide::editor` is italic.
- Reworked go-to-declaration and hover around symbol identity and use-site role instead of spelling alone. Lexical scopes, shadowing, receiver members, types, callables, exact local/parameter offsets, documentation, stdlib sources, and path dependencies are resolved.
- Fixed exact-prefix completion, including the `Anchor`/`TilemapAnchor` suffix collision, and made annotation and macro completion follow current `@Name`/`@name` syntax.
- Added precise unused-local warnings and quick fixes for lower/UpperCamelCase naming, illegal underscore forms, constructor and enum shorthand, floating-point literals or explicit casts, import typos, annotation typos, strings, and brackets.
- Updated `.az` indentation and added complete `.azon` Enter-key indentation, code-style settings, and Reformat Code support for nested comma-free objects and arrays. Structure view, run markers, project scaffolds, folding, snippets, and live templates now use current syntax: `react func`, `react ctor`, `async func`, current receiver parameters, exact ECS query syntax, and comma-free pack/enum members.
- Added regressions against real compiler and engine sources for contextual coloring, string interpolation references, C bridge names, current macros, external symbols, and same-spelled symbols with different meanings.

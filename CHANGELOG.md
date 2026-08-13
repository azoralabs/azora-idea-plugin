# Changelog

## 0.0.7

- Updated syntax and contextual keyword highlighting to the current Azora vocabulary.
- Fixed complete italic highlighting for realm-qualified paths such as `ide::editor`.
- Restored stdlib and dependency symbol visibility for current `module`/`realm` sources and legacy `mod`/`zone` SDK sources.
- Reworked go-to-declaration selection around use-site symbol roles, including local shadowing, member receivers, types, callables, and exact local/parameter declaration targets.
- Scoped generic type-parameter highlighting to its declaring construct.

# Contributing to the Azora IntelliJ Plugin

Thank you for your interest in contributing to the Azora IDE plugin. This document outlines the rules and expectations for all contributors. These rules are **strictly enforced**, pull requests that violate them will be rejected without review.

## Rules

### 1. Open an Issue First

**Every change must have a corresponding issue.** Do not open a pull request without an approved issue. This applies to bug fixes, features, refactors, and documentation changes alike. The only exceptions are typo fixes of three words or fewer.

### 2. One Pull Request, One Concern

Each pull request must address **exactly one issue**. Do not bundle unrelated changes. Do not sneak in refactors, formatting changes, or "improvements" alongside a bug fix. If you find something else that needs fixing, open a separate issue.

### 3. No Breaking Changes Without Discussion

Any change that alters plugin behavior visible to users, modifies the public API surface of services like `AzoraSymbolService`, changes file type registration, or removes/renames existing functionality **must** be discussed in the issue before implementation. Open a GitHub Issue describing the change and wait for maintainer approval before writing code.

### 4. Tests Are Mandatory

- Every bug fix must include a test that reproduces the bug.
- Every new feature must include tests covering its behavior.
- All existing tests must pass. Run `./gradlew build` locally before pushing.
- The CI build must pass before a PR can be merged.

### 5. Code Style

- Follow the existing code style exactly. Do not reformat files you did not change.
- Kotlin code follows [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- All public and internal classes, methods, and properties must have KDoc comments.
- No trailing whitespace. No unused imports. No commented-out code.
- Do not use the em dash character, use a comma instead.

### 6. Commit Discipline

- Write clear, imperative commit messages: `Fix completion crash on empty file` not `fixed stuff`.
- Each commit must compile and pass tests independently.
- Squash fixup commits before requesting review. The final PR should have a clean, logical commit history.
- Do **not** include merge commits. Rebase onto `main` before submitting.

### 7. Documentation

- Public API changes must include KDoc updates.
- New services, providers, or extension points must include class-level and method-level documentation.
- Update `README.md` if the change adds or removes a user-facing feature.

### 8. No Dependencies Without Approval

Do not add new third-party dependencies without prior approval in the issue discussion. The plugin relies only on the IntelliJ Platform SDK and the Kotlin standard library. If a dependency is necessary, justify it clearly and consider the impact on plugin size and compatibility.

### 9. IntelliJ Platform Compatibility

- Do not use deprecated IntelliJ Platform APIs. If you encounter a deprecation, use the recommended replacement.
- The plugin must build and run on IntelliJ IDEA 2025.3+ and Android Studio Meerkat+.
- Test your changes against the `sinceBuild` version declared in `build.gradle.kts`.

### 10. Licensing

All contributions are submitted under the [Apache License 2.0](LICENSE). By submitting a pull request, you certify that you have the right to submit the code under this license and that you agree to the [Developer Certificate of Origin](https://developercertificate.org/).

All source files must include the standard copyright header:

```
/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

### 11. Review Process

- All pull requests require at least one approving review from a maintainer.
- Address all review comments. Do not resolve conversations yourself, let the reviewer resolve them.
- Do not force-push after review has started. Push new commits so reviewers can see the delta.
- Maintainers may close stale PRs (no activity for 30 days) without merging.

## Setting Up Your Development Environment

```sh
# Clone your fork
git clone https://github.com/<your-username>/azora-idea-plugin.git
cd azora-idea-plugin

# Build
./gradlew build

# Run the plugin in a sandbox IDE
./gradlew runIde
```

### Prerequisites

- JDK 17+
- Gradle 9.1+ (wrapper included)
- [IntelliJ IDEA](https://www.jetbrains.com/idea/) or [Android Studio](https://developer.android.com/studio) for local development

The build automatically detects whether Android Studio is installed locally. In CI or when the local IDE is absent, it downloads IntelliJ IDEA Community Edition.

## Project Structure

```
src/main/kotlin/org/azora/lang/idea/
  AzoraLanguage.kt              # Language definition
  AzoraFileType.kt              # File type registration (.az)
  AzoraFile.kt                  # PSI file implementation
  AzoraTokenTypes.kt            # Token type definitions
  AzoraLanguageFacts.kt         # Keyword sets, mirroring the compiler's lexer
  AzoraLexerAdapter.kt          # Standalone lexer (strings, interpolation, macros)
  AzoraParserDefinition.kt      # Parser definition (flat PSI)
  AzoraBraceMatcher.kt          # Brace matching
  AzoraCommenter.kt             # Line/block commenting
  AzoraIcons.kt                 # Icon registry
  annotator/                    # Diagnostics, quick fixes, semantic highlighting
  azon/                         # The AZON data language: reader, lexer, checks
  build/                        # Manifest reading (.azon / legacy .toml) and sync
  completion/                   # Code completion, snippets, live-template context
  documentation/                # Quick documentation provider
  findusages/                   # Find usages provider
  folding/                      # Code folding
  formatting/                   # Code style, formatter, line indent
  highlighting/                 # Palette, attribute keys, semantic classification
  hints/                        # Inferred-type inlay hints
  navigation/                   # Go-to-declaration
  project/                      # New project wizard, templates, SDK settings
  run/                          # Run/test configurations and gutter icons
  structure/                    # Structure view
  symbol/                       # Symbol extraction, resolution, macro index
```

### Two rules worth knowing

**Nothing about a specific Azora name is hardcoded.** Keyword sets in
`AzoraLanguageFacts` mirror the compiler's own table and are the only such list.
The standard library, macros, modules, and types are all indexed from real `.az`
sources. If you find yourself adding a name to a list, look for the declaration
that should have told you instead.

**Lexical decisions belong to the lexer, semantic ones to the annotator.**
Whether `with` is a keyword or a macro cannot be decided from the characters
alone, so `AzoraSemanticModel` decides it from position plus the macro index,
and `AzoraSemanticAnnotator` paints the result.

## What to Work On

Look for issues tagged `good first issue` or `help wanted`. If you want to work on something, comment on the issue to claim it before starting. Do not start work on issues already assigned to someone else.

## Reporting Bugs

When reporting a bug, include:

1. Plugin version
2. IDE name and version (IntelliJ IDEA / Android Studio, build number)
3. Operating system and JDK version
4. Steps to reproduce
5. Expected behavior
6. Actual behavior (include screenshots or error logs if applicable)

## Requesting Features

Feature requests must include:

1. A clear description of the problem the feature solves
2. How the feature should behave in the IDE (UI mockups or descriptions welcome)
3. How it interacts with existing plugin features
4. Whether it requires changes to the Azora language or SDK

## Links

- [Azora Website](https://azoralang.org)
- [Azora Documentation](https://docs.azoralang.org)
- [The Azora Book](https://book.azoralang.org)
- [Azora Playground](https://code.azoralang.org)
- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/welcome.html)

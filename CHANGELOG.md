# Changelog

## Unreleased

### Added
- Grass, leaves and other biome-coloured materials are tinted like the full block.
- Crafting recipes for the chisels and the hand drills (iron nugget, iron ingot or flint with a stick).
- Russian translation.

### Fixed
- The outline of a carved block follows the box of each part instead of every cell.
- Faces on the border between two carved blocks are cut by what the neighbour covers: no doubled faces inside.
- The table recipe and its drop were missing on 1.18.2 – 1.21.1 (wrong data folders and recipe format).
- 26.2 and 26.3 use the fast selection shape (was the slow path capped at 64 boxes).
- A tool can work at most once per tick per player, whatever the client sends.

## 0.1.0
- First version.

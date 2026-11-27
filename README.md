# Zeno

A lightweight 2D / 2.5D game engine in active development, focused on flexible architecture and a smooth build pipeline.

## Current Status
- **Stage**: Prototype / early iteration (APIs and folder structure may change frequently)
- **Target Platform**: Android (additional platforms may be explored later)

## Core Features (Roadmap & Progress)
- [x] Basic project setup and build pipeline
- [ ] Main loop, scene, and entity management
- [ ] 2D / 2.5D rendering and input handling
- [ ] Debug tools / editor interface

## Project Structure
- `app/`: Main application / runtime sample / editor entry point
- `.github/workflows/`: Automated build and packaging workflows
- `build.gradle.kts` / `settings.gradle.kts`: Gradle build scripts

## Build & Run

### Option 1: GitHub Actions
1. Push changes to a branch (such as `main`) or manually trigger the corresponding workflow.
2. Check the build status under the **Actions** tab and download build artifacts (such as an APK) once finished.

### Option 2: Local / Mobile Environment
1. Set up a compatible JDK along with the Android SDK and Gradle.
2. Run the build command from the project root:
   ```bash
   ./gradlew assembleDebug
   ```

## Dev Notes / Known Issues
Track blockers, performance bottlenecks, or temporary workarounds here so you can pick up easily later.

## License & Notes
Currently in personal development (all rights reserved until explicitly licensed; add a license such as MIT or Apache-2.0 if open-sourced later).
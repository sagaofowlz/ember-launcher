# Publish this source to your GitHub account

The source package is ready, but it has not been published because the connected GitHub actions available in this session cannot create a brand-new repository.

1. Open https://github.com/new in your browser.
2. Create an **empty** repository named `ember-launcher`. Do not initialize it with a README, license or `.gitignore` (those files are already included here).
3. Extract `ember-launcher-source.zip` on your computer, open a terminal in the extracted `ember-launcher` folder, then run:

```bash
git init
git add .
git commit -m "Initial Ember Launcher MVP"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/ember-launcher.git
git push -u origin main
```

Replace `YOUR-USERNAME` with your GitHub username. Git may open a browser sign-in prompt.

After the push, open the repository's **Actions** tab. The `Android APK` workflow will build a debug APK and attach `ember-launcher-debug-apk` as an artifact. Download that artifact from the completed workflow run.

You can also open the project directly in Android Studio, install Android SDK Platform 35 and JDK 17, then run `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`).

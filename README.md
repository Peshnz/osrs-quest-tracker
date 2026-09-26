# OSRS Optimal Quest Tracker

Every Old School RuneScape quest in the OSRS Wiki optimal quest guide order, with difficulty, length, wiki guide links, status tracking and one-tap sync from RuneProfile.

## Publish it on GitHub Pages

1. Sign in at github.com (or create a free account).
2. Click **+** (top right) → **New repository**. Name it e.g. `osrs-quest-tracker`, set it to **Public**, and click **Create repository**.
3. On the new repo page, click **uploading an existing file**.
4. Drag in everything from this folder: `index.html`, `manifest.webmanifest`, `sw.js`, `README.md` and the `icons` folder. Click **Commit changes**.
5. Go to **Settings → Pages**. Under **Branch**, pick `main` and `/ (root)`, then **Save**.
6. Wait a minute or two, refresh the Pages settings page, and your link appears:
   `https://YOUR-USERNAME.github.io/osrs-quest-tracker/`

## Put it on your phone like an app

- **iPhone (Safari):** open the link → Share → **Add to Home Screen**.
- **Android (Chrome):** open the link → ⋮ menu → **Install app** / **Add to Home screen**.

It opens full screen with its own icon and works offline. Your quest statuses are saved on each device.

## Updating it later

Upload the changed file over the old one in GitHub. Then open `sw.js`, change `quest-tracker-v1` to `v2` (then `v3`, and so on) and commit, so phones fetch the new version.

## Syncing quests

- **RuneProfile (automatic):** install the RuneProfile plugin in RuneLite and log in once. Then enter your name and tap **Sync quests**.
- **WikiSync (copy and paste):** under **Use WikiSync instead**, open your data, copy it all and paste it into the box.

## Credits

Quest order, names, difficulty and length come from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/), used under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). Keep this project non-commercial and keep the credit. Quest sync by [RuneProfile](https://www.runeprofile.com/). Fan-made and not affiliated with Jagex.

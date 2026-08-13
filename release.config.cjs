const conventionalCommits = {
  preset: "conventionalcommits",
  presetConfig: {},
};

function repositoryUrlFromEnvironment() {
  const repository = process.env.TAPZIQ_RELEASE_REPOSITORY;
  if (repository === undefined) {
    return undefined;
  }

  const components = repository.split("/");
  const validComponent = /^[A-Za-z0-9_.-]+$/;
  if (
    components.length !== 2
    || components.some((component) => (
      !validComponent.test(component)
      || component === "."
      || component === ".."
    ))
  ) {
    throw new Error(
      "TAPZIQ_RELEASE_REPOSITORY must be a canonical owner/repository name.",
    );
  }

  return `https://github.com/${repository}.git`;
}

const releaseConfig = {
  branches: ["main"],
  tagFormat: "v${version}",
  plugins: [
    ["@semantic-release/commit-analyzer", conventionalCommits],
    ["@semantic-release/release-notes-generator", conventionalCommits],
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          "./scripts/package-semantic-release.sh "
          + "${nextRelease.version} ${nextRelease.gitHead}",
      },
    ],
    [
      "@semantic-release/github",
      {
        assets: [
          {
            path: "dist/release/Tapziq-v*.apk",
            label: "Production-signed Android APK",
          },
          {
            path: "dist/release/SHA256SUMS",
            label: "SHA-256 checksums",
          },
          {
            path: "dist/release/LICENSE.txt",
            label: "Apache License 2.0",
          },
          {
            path: "dist/release/THIRD_PARTY_NOTICES.md",
            label: "Third-party notices",
          },
        ],
        releaseNameTemplate: "Tapziq Keyboard <%= nextRelease.version %>",
        releaseBodyTemplate:
          "Download **`Tapziq-v<%= nextRelease.version %>.apk`** to install "
          + "this release on Android 8.0 or newer.\n\n"
          + "<%= nextRelease.notes %>\n\n"
          + "### Release verification\n\n"
          + "The production-signed APK, license, notices, and `SHA256SUMS` "
          + "were built and verified together from the tagged source commit. "
          + "The APK is signed by the permanent Tapziq release key.",
        draftRelease: false,
        successComment: false,
        failComment: false,
        labels: false,
        releasedLabels: false,
        addReleases: false,
      },
    ],
  ],
};

const repositoryUrl = repositoryUrlFromEnvironment();
if (repositoryUrl !== undefined) {
  releaseConfig.repositoryUrl = repositoryUrl;
}

module.exports = releaseConfig;

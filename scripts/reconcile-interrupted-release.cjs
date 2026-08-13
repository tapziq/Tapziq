#!/usr/bin/env node
"use strict";

const { execFileSync, spawnSync } = require("node:child_process");
const { appendFileSync, readFileSync } = require("node:fs");
const { createHash } = require("node:crypto");
const { pathToFileURL } = require("node:url");
const path = require("node:path");
const process = require("node:process");
const semver = require("semver");

const TRUSTED_REPOSITORY_ID = "1332440403";
const TAG_PATTERN = /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const RELEASE_ASSETS = (version) => [
  {
    name: `Tapziq-v${version}.apk`,
    label: "Production-signed Android APK",
    contentType: "application/vnd.android.package-archive",
  },
  {
    name: "LICENSE.txt",
    label: "Apache License 2.0",
    contentType: "text/plain",
  },
  {
    name: "SHA256SUMS",
    label: "SHA-256 checksums",
    contentType: "text/plain",
  },
  {
    name: "THIRD_PARTY_NOTICES.md",
    label: "Third-party notices",
    contentType: "text/markdown",
  },
];
const RELEASE_ASSET_NAMES = (version) => RELEASE_ASSETS(version).map(({ name }) => name);
const CONVENTIONAL_COMMITS = {
  preset: "conventionalcommits",
  presetConfig: {},
};

function fail(message) {
  throw new Error(message);
}

function run(command, args, options = {}) {
  const result = execFileSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
    ...options,
  });
  return typeof result === "string" ? result.trim() : result;
}

function git(...args) {
  return run("git", args);
}

function ghApi(endpoint, fields = []) {
  return JSON.parse(run("gh", ["api", endpoint, ...fields]));
}

function ghApiWithoutResponse(endpoint, fields = []) {
  run("gh", ["api", endpoint, ...fields]);
}

function releaseApiOrNull(endpoint) {
  try {
    return ghApi(endpoint);
  } catch (error) {
    if (error.status === 1 && /HTTP 404|Not Found/.test(error.stderr || "")) {
      return null;
    }
    throw error;
  }
}

function ghApiWithBinaryInput(endpoint, fields, content) {
  const result = spawnSync("gh", ["api", endpoint, ...fields], {
    cwd: repositoryRoot,
    input: content,
    maxBuffer: 16 * 1024 * 1024,
    stdio: ["pipe", "pipe", "pipe"],
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    const error = new Error("GitHub asset upload failed.");
    error.status = result.status;
    error.stderr = result.stderr.toString("utf8");
    throw error;
  }
}

function setHandled(value) {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (outputPath) {
    appendFileSync(outputPath, `handled=${value}\n`, "utf8");
  }
  process.stdout.write(`handled=${value}\n`);
}

function parseRemoteRef(output, expectedRef) {
  const rows = output === "" ? [] : output.split("\n");
  if (rows.length !== 1) {
    fail(`Expected exactly one remote ${expectedRef} reference.`);
  }
  const [sha, ref, extra] = rows[0].split(/\s+/);
  if (extra !== undefined || ref !== expectedRef || !/^[0-9a-f]{40}$/.test(sha)) {
    fail(`Remote ${expectedRef} reference is malformed.`);
  }
  return sha;
}

function resolveRemoteTag(tag) {
  const directRef = `refs/tags/${tag}`;
  const peeledRef = `${directRef}^{}`;
  const output = git("ls-remote", repositoryUrl, directRef, peeledRef);
  const rows = output === "" ? [] : output.split("\n");
  if (rows.length < 1 || rows.length > 2) {
    fail(`Expected one remote tag named ${tag}.`);
  }
  const refs = new Map();
  for (const row of rows) {
    const [sha, ref, extra] = row.split(/\s+/);
    if (
      extra !== undefined
      || ![directRef, peeledRef].includes(ref)
      || refs.has(ref)
      || !/^[0-9a-f]{40}$/.test(sha)
    ) {
      fail(`Remote release tag ${tag} is malformed.`);
    }
    refs.set(ref, sha);
  }
  if (!refs.has(directRef)) {
    fail(`Remote release tag ${tag} is missing.`);
  }
  return refs.get(peeledRef) || refs.get(directRef);
}

function parseSemanticReleaseNote(tag) {
  const noteRef = `refs/notes/semantic-release-${tag}`;
  const remoteNoteRef = git("ls-remote", repositoryUrl, noteRef);
  if (remoteNoteRef === "") {
    fail(`Remote semantic-release note is missing for ${tag}.`);
  }
  parseRemoteRef(remoteNoteRef, noteRef);
  git("fetch", "--force", "--no-tags", repositoryUrl, `+${noteRef}:${noteRef}`);
  const note = git("notes", "--ref", `semantic-release-${tag}`, "show", tag);
  let parsed;
  try {
    parsed = JSON.parse(note);
  } catch {
    fail(`Semantic-release note is invalid for ${tag}.`);
  }
  if (
    parsed === null
    || Array.isArray(parsed)
    || typeof parsed !== "object"
    || Object.keys(parsed).length !== 1
    || !Array.isArray(parsed.channels)
    || parsed.channels.length !== 1
    || parsed.channels[0] !== null
  ) {
    fail(`Semantic-release note is not the stable-channel marker for ${tag}.`);
  }
}

function expectedVersion(previousVersion, releaseType) {
  const next = semver.inc(previousVersion, releaseType);
  if (!next) {
    fail(`Cannot increment ${previousVersion} as a ${releaseType} release.`);
  }
  return next;
}

function sameAssetNames(release, version) {
  const actual = release.assets.map(({ name }) => name).sort();
  const expected = RELEASE_ASSET_NAMES(version).sort();
  return JSON.stringify(actual) === JSON.stringify(expected);
}

function isAlreadyPublishedRelease(release, tag, version, expectedCommit, body) {
  return release.tag_name === tag
    && release.name === `Tapziq Keyboard ${version}`
    && release.draft === false
    && release.prerelease === false
    && release.immutable === true
    && ["main", expectedCommit].includes(release.target_commitish)
    && release.body === body
    && release.assets.length === 4
    && sameAssetNames(release, version)
    && release.assets.every((asset) => {
      const expected = RELEASE_ASSETS(version).find(({ name }) => name === asset.name);
      return expected !== undefined
        && asset.label === expected.label
        && asset.content_type === expected.contentType
        && Number.isSafeInteger(asset.size)
        && asset.size > 0
        && typeof asset.digest === "string"
        && /^sha256:[0-9a-f]{64}$/.test(asset.digest);
    });
}

function matchingDraftMetadata(release, tag, version, expectedCommit, expectedBody) {
  return release.tag_name === tag
    && release.name === `Tapziq Keyboard ${version}`
    && release.draft === true
    && release.prerelease === false
    && release.immutable === false
    && ["main", expectedCommit].includes(release.target_commitish)
    && release.body === expectedBody;
}

function releaseBody(version, notes) {
  return `Download **\`Tapziq-v${version}.apk\`** to install this release on Android 8.0 or newer.\n\n`
    + `${notes}\n\n`
    + "### Release verification\n\n"
    + "The production-signed APK, license, notices, and `SHA256SUMS` were built and verified together from the tagged source commit. "
    + "The APK is signed by the permanent Tapziq release key.";
}

async function analyzeAndGenerate(previousTag, currentTag, currentCommit) {
  git("merge-base", "--is-ancestor", previousTag, currentCommit);
  const semanticReleaseRoot = path.dirname(require.resolve("semantic-release/package.json"));
  const { getCommits } = await import(pathToFileURL(
    path.join(semanticReleaseRoot, "lib", "git.js"),
  ));
  const commits = await getCommits(previousTag, currentCommit, {
    cwd: repositoryRoot,
    env: process.env,
  });
  if (commits.length === 0) {
    fail(`No commits exist between ${previousTag} and the orphan tag.`);
  }
  const { analyzeCommits } = await import("@semantic-release/commit-analyzer");
  const { generateNotes } = await import("@semantic-release/release-notes-generator");
  const logger = { log() {} };
  const releaseType = await analyzeCommits(CONVENTIONAL_COMMITS, {
    commits,
    cwd: repositoryRoot,
    logger,
  });
  if (releaseType === null) {
    fail(`The commits since ${previousTag} do not justify ${currentTag}.`);
  }
  const previousVersion = previousTag.slice(1);
  const version = currentTag.slice(1);
  if (expectedVersion(previousVersion, releaseType) !== version) {
    fail(`${currentTag} is not the expected ${releaseType} release after ${previousTag}.`);
  }
  const notes = await generateNotes(CONVENTIONAL_COMMITS, {
    commits,
    cwd: repositoryRoot,
    lastRelease: {
      gitHead: git("rev-list", "-n", "1", previousTag),
      gitTag: previousTag,
    },
    nextRelease: {
      gitHead: currentCommit,
      gitTag: currentTag,
      version,
    },
    options: { repositoryUrl },
  });
  return { notes, version };
}

function fileSha256(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function inspectDraftAssets(assets, version, releaseDirectory) {
  if (!Array.isArray(assets) || assets.length > RELEASE_ASSETS(version).length) {
    fail("The draft contains an invalid release asset inventory.");
  }
  const matchingAssets = new Map();
  const staleAssets = [];
  const seenNames = new Set();
  const seenIds = new Set();
  for (const asset of assets) {
    const expected = RELEASE_ASSETS(version).find(({ name }) => name === asset.name);
    if (
      expected === undefined
      || seenNames.has(asset.name)
      || seenIds.has(asset.id)
      || asset.label !== expected.label
      || asset.content_type !== expected.contentType
      || asset.state !== "uploaded"
      || !Number.isSafeInteger(asset.id)
      || asset.id <= 0
      || !Number.isSafeInteger(asset.size)
      || asset.size <= 0
      || !/^sha256:[0-9a-f]{64}$/.test(asset.digest || "")
    ) {
      fail("The draft contains an unexpected or ambiguous release asset.");
    }
    const localPath = path.join(releaseDirectory, asset.name);
    const localContent = readFileSync(localPath);
    seenNames.add(asset.name);
    seenIds.add(asset.id);
    if (
      asset.size !== localContent.length
      || asset.digest !== `sha256:${fileSha256(localPath)}`
    ) {
      staleAssets.push(asset);
    } else {
      matchingAssets.set(asset.name, asset);
    }
  }
  return { matchingAssets, staleAssets };
}

function uploadAssets(draft, version) {
  const releaseDirectory = path.join(repositoryRoot, "dist", "release");
  if (!Number.isSafeInteger(draft.id) || draft.id <= 0) {
    fail("GitHub returned an invalid draft release ID.");
  }
  const expectedPath = `/repos/${repositoryFullName}/releases/${draft.id}/assets`;
  const uploadUrl = new URL(draft.upload_url.replace("{?name,label}", ""));
  if (
    uploadUrl.protocol !== "https:"
    || uploadUrl.hostname !== "uploads.github.com"
    || uploadUrl.pathname !== expectedPath
    || uploadUrl.search !== ""
  ) {
    fail("GitHub returned an unexpected release upload URL.");
  }
  let { matchingAssets, staleAssets } = inspectDraftAssets(
    draft.assets,
    version,
    releaseDirectory,
  );
  for (const asset of staleAssets) {
    ghApiWithoutResponse(
      `repositories/${TRUSTED_REPOSITORY_ID}/releases/assets/${asset.id}`,
      ["--method", "DELETE"],
    );
  }
  if (staleAssets.length > 0) {
    const afterDeletion = ghApi(
      `repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`,
    );
    if (!matchingDraftMetadata(
      afterDeletion,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )) {
      fail("Draft release changed unexpectedly while stale assets were removed.");
    }
    ({ matchingAssets, staleAssets } = inspectDraftAssets(
      afterDeletion.assets,
      version,
      releaseDirectory,
    ));
    if (staleAssets.length > 0) {
      fail("GitHub retained a stale draft asset after deletion.");
    }
  }
  for (const asset of RELEASE_ASSETS(version)) {
    if (matchingAssets.has(asset.name)) {
      continue;
    }
    const content = readFileSync(path.join(releaseDirectory, asset.name));
    ghApiWithBinaryInput(
      `${uploadUrl.href}?name=${encodeURIComponent(asset.name)}&label=${encodeURIComponent(asset.label)}`,
      [
        "--method", "POST",
        "-H", `Content-Type: ${asset.contentType}`,
        "--input", "-",
      ],
      content,
    );
  }
  const refreshed = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`);
  if (
    !matchingDraftMetadata(
      refreshed,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )
    || refreshed.assets.length !== RELEASE_ASSET_NAMES(version).length
    || !sameAssetNames(refreshed, version)
  ) {
    fail("Draft release changed unexpectedly while assets were uploaded.");
  }
  const finalAssets = inspectDraftAssets(
    refreshed.assets,
    version,
    releaseDirectory,
  );
  if (
    finalAssets.staleAssets.length !== 0
    || finalAssets.matchingAssets.size !== RELEASE_ASSET_NAMES(version).length
  ) {
    fail("The complete draft does not match the freshly verified release package.");
  }
}

const repositoryRoot = path.resolve(__dirname, "..");
let repositoryUrl;
let repositoryFullName;
let currentTag;
let expectedCommit;
let expectedBody;

async function main() {
  if (process.env.GITHUB_REF !== "refs/heads/main") {
    fail("Interrupted release reconciliation is restricted to main.");
  }
  if (process.env.GITHUB_REPOSITORY_ID !== TRUSTED_REPOSITORY_ID) {
    fail("Interrupted release reconciliation is restricted to the trusted Tapziq repository.");
  }
  expectedCommit = (process.env.GITHUB_SHA || "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(expectedCommit)) {
    fail("GITHUB_SHA must be a full Git commit SHA.");
  }
  if (git("rev-parse", "HEAD") !== expectedCommit) {
    fail("The checkout does not match GITHUB_SHA.");
  }
  if (git("status", "--porcelain", "--untracked-files=normal") !== "") {
    fail("Interrupted release reconciliation requires a clean worktree.");
  }

  const repository = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}`);
  if (
    String(repository.id) !== TRUSTED_REPOSITORY_ID
    || repository.archived
    || repository.disabled
    || repository.default_branch !== "main"
  ) {
    fail("The trusted Tapziq repository is not an active main-branch repository.");
  }
  const repositoryComponents = typeof repository.full_name === "string"
    ? repository.full_name.split("/")
    : [];
  if (
    repositoryComponents.length !== 2
    || repositoryComponents.some((component) => (
      !/^[A-Za-z0-9_.-]+$/.test(component)
      || component === "."
      || component === ".."
    ))
  ) {
    fail("The trusted Tapziq repository name is invalid.");
  }
  if (process.env.GITHUB_REPOSITORY !== repository.full_name) {
    fail("GITHUB_REPOSITORY does not resolve to the trusted Tapziq repository.");
  }
  repositoryFullName = repository.full_name;
  repositoryUrl = `https://github.com/${repositoryFullName}.git`;
  const remoteHead = parseRemoteRef(
    git("ls-remote", repositoryUrl, "refs/heads/main"),
    "refs/heads/main",
  );
  if (remoteHead !== expectedCommit) {
    fail("Remote main no longer matches the release source commit.");
  }

  const releaseTags = git("tag", "--points-at", expectedCommit)
    .split("\n")
    .filter((tag) => TAG_PATTERN.test(tag));
  if (releaseTags.length === 0) {
    setHandled(false);
    return;
  }
  if (releaseTags.length !== 1) {
    fail("Expected at most one semantic-version tag on the release source commit.");
  }
  [currentTag] = releaseTags;
  const version = currentTag.slice(1);
  const remoteTag = resolveRemoteTag(currentTag);
  if (git("rev-list", "-n", "1", currentTag) !== expectedCommit || remoteTag !== expectedCommit) {
    fail("The local and remote release tags do not both resolve to GITHUB_SHA.");
  }
  parseSemanticReleaseNote(currentTag);

  const mergedReleaseTags = git("tag", "--merged", expectedCommit)
    .split("\n")
    .filter((tag) => TAG_PATTERN.test(tag));
  const currentOrNewerTags = mergedReleaseTags.filter(
    (tag) => !semver.lt(tag.slice(1), version),
  );
  if (currentOrNewerTags.length !== 1 || currentOrNewerTags[0] !== currentTag) {
    fail("Additional semantic-version tags make release provenance ambiguous.");
  }
  const previousTags = mergedReleaseTags
    .filter((tag) => semver.lt(tag.slice(1), version))
    .sort((first, second) => semver.rcompare(first.slice(1), second.slice(1)));
  if (previousTags.length === 0) {
    fail(`Could not resolve the previous stable release before ${currentTag}.`);
  }
  const [previousTag] = previousTags;
  const previousRemoteTag = resolveRemoteTag(previousTag);
  if (git("rev-list", "-n", "1", previousTag) !== previousRemoteTag) {
    fail("The previous release tag has inconsistent local and remote provenance.");
  }
  const result = await analyzeAndGenerate(previousTag, currentTag, expectedCommit);
  expectedBody = releaseBody(result.version, result.notes);

  const existingRelease = releaseApiOrNull(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases/tags/${currentTag}`,
  );
  if (existingRelease && existingRelease.draft === false) {
    if (!isAlreadyPublishedRelease(
      existingRelease,
      currentTag,
      version,
      expectedCommit,
      expectedBody,
    )) {
      fail(`Published release ${currentTag} does not satisfy the release contract.`);
    }
    run(path.join(repositoryRoot, "scripts", "verify-published-release.sh"), [
      version,
      expectedCommit,
    ], { stdio: "inherit" });
    setHandled(true);
    return;
  }

  const latestPublished = ghApi(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases/latest`,
  );
  if (!latestPublished || !TAG_PATTERN.test(latestPublished.tag_name)) {
    fail("Could not resolve GitHub's latest stable Tapziq release.");
  }
  if (latestPublished.tag_name !== previousTag) {
    fail("The previous semantic-version tag is not GitHub's latest stable release.");
  }

  const drafts = ghApi(
    `repositories/${TRUSTED_REPOSITORY_ID}/releases?per_page=100`,
  ).filter((release) => release.tag_name === currentTag);
  if (drafts.length > 1) {
    fail(`Multiple GitHub releases use tag ${currentTag}.`);
  }
  if (existingRelease && !drafts.some(({ id }) => id === existingRelease.id)) {
    fail(`The API returned inconsistent release state for ${currentTag}.`);
  }
  let draft = drafts[0];
  if (draft && !matchingDraftMetadata(
    draft,
    currentTag,
    result.version,
    expectedCommit,
    expectedBody,
  )) {
    fail(`Existing draft ${currentTag} does not exactly match the expected release.`);
  }

  run(path.join(repositoryRoot, "scripts", "package-semantic-release.sh"), [
    result.version,
    expectedCommit,
    "--allow-existing-tag",
  ], { stdio: "inherit" });

  if (!draft) {
    draft = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases`, [
      "--method", "POST",
      "-f", `tag_name=${currentTag}`,
      "-f", `target_commitish=${expectedCommit}`,
      "-f", `name=Tapziq Keyboard ${result.version}`,
      "-f", `body=${expectedBody}`,
      "-F", "draft=true",
      "-F", "prerelease=false",
      "-f", "make_latest=true",
    ]);
    if (!matchingDraftMetadata(
      draft,
      currentTag,
      result.version,
      expectedCommit,
      expectedBody,
    ) || draft.assets.length !== 0) {
      fail("GitHub did not create the exact expected draft release.");
    }
  }

  uploadAssets(draft, result.version);
  const published = ghApi(`repositories/${TRUSTED_REPOSITORY_ID}/releases/${draft.id}`, [
    "--method", "PATCH",
    "-F", "draft=false",
    "-f", "make_latest=true",
  ]);
  if (published.draft !== false || published.tag_name !== currentTag) {
    fail(`GitHub did not publish ${currentTag}.`);
  }
  run(path.join(repositoryRoot, "scripts", "verify-published-release.sh"), [
    result.version,
    expectedCommit,
  ], { stdio: "inherit" });
  setHandled(true);
}

main().catch((error) => {
  process.stderr.write(`ERROR: ${error.message}\n`);
  process.exitCode = 1;
});

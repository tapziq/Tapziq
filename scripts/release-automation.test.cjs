const assert = require("node:assert/strict");
const { execFileSync, spawnSync } = require("node:child_process");
const {
  chmodSync,
  cpSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..");
const releaseConfigPath = path.join(repositoryRoot, "release.config.cjs");
const workflow = readFileSync(
  path.join(repositoryRoot, ".github", "workflows", "release.yml"),
  "utf8",
);
const versionCodeScript = path.join(
  repositoryRoot,
  "scripts",
  "semantic-version-code.sh",
);
const packageScript = path.join(
  repositoryRoot,
  "scripts",
  "package-semantic-release.sh",
);
const publishedVerifierScript = readFileSync(
  path.join(repositoryRoot, "scripts", "verify-published-release.sh"),
  "utf8",
);
const apkVerifierScript = readFileSync(
  path.join(repositoryRoot, "scripts", "verify-release-apk.sh"),
  "utf8",
);
const emulatorSmokeScript = readFileSync(
  path.join(repositoryRoot, "scripts", "smoke-test-release-apk.sh"),
  "utf8",
);

function writeExecutable(filePath, contents) {
  writeFileSync(filePath, contents, "utf8");
  chmodSync(filePath, 0o755);
}

function createPackageFixture({
  tagTarget = "head",
  tagExists = true,
  outputSymlink = null,
} = {}) {
  const fixtureRoot = mkdtempSync(path.join(os.tmpdir(), "tapziq-package-test-"));
  const fixtureRepository = path.join(fixtureRoot, "repository");
  const fixtureBin = path.join(fixtureRoot, "bin");
  mkdirSync(fixtureRepository);
  mkdirSync(fixtureBin);
  for (const directory of ["scripts", "app/build/outputs/apk/release"]) {
    mkdirSync(path.join(fixtureRepository, directory), { recursive: true });
  }
  for (const fileName of ["LICENSE", "THIRD_PARTY_NOTICES.md"]) {
    writeFileSync(path.join(fixtureRepository, fileName), `${fileName}\n`);
  }
  writeFileSync(
    path.join(fixtureRepository, "app/build/outputs/apk/release/app-release.apk"),
    "fixture apk\n",
  );
  cpSync(packageScript, path.join(fixtureRepository, "scripts", path.basename(packageScript)));
  writeExecutable(
    path.join(fixtureRepository, "scripts", "semantic-version-code.sh"),
    "#!/usr/bin/env bash\nprintf '1001\\n'\n",
  );
  writeExecutable(
    path.join(fixtureRepository, "scripts", "build-production-release.sh"),
    "#!/usr/bin/env bash\nexit 0\n",
  );
  writeExecutable(
    path.join(fixtureRepository, "scripts", "verify-release-assets.sh"),
    "#!/usr/bin/env bash\nexit 0\n",
  );
  writeExecutable(
    path.join(fixtureBin, "git"),
    `#!/usr/bin/env bash
case "$*" in
  *" rev-parse HEAD") printf '%s\\n' "$FIXTURE_HEAD" ;;
  *" show-ref --verify --quiet refs/tags/v0.1.1") [[ "$FIXTURE_TAG_EXISTS" == true ]] ;;
  *" rev-parse --verify v0.1.1^{commit}") printf '%s\\n' "$FIXTURE_TAG_TARGET" ;;
  *" status --porcelain --untracked-files=normal") exit 0 ;;
  *) printf 'Unexpected git invocation: %s\\n' "$*" >&2; exit 99 ;;
esac
`,
  );
  const head = "1".repeat(40);
  const resolvedTagTarget = tagTarget === "head" ? head : "2".repeat(40);
  if (outputSymlink !== null) {
    const outsideDirectory = path.join(fixtureRoot, "outside");
    mkdirSync(outsideDirectory);
    writeFileSync(path.join(outsideDirectory, "sentinel"), "keep\n");
    if (outputSymlink === "dist") {
      symlinkSync(outsideDirectory, path.join(fixtureRepository, "dist"));
    } else {
      assert.equal(outputSymlink, "release");
      mkdirSync(path.join(fixtureRepository, "dist"));
      symlinkSync(outsideDirectory, path.join(fixtureRepository, "dist", "release"));
    }
  }
  return {
    fixtureRoot,
    fixtureRepository,
    environment: {
      ...process.env,
      FIXTURE_HEAD: head,
      FIXTURE_TAG_EXISTS: String(tagExists),
      FIXTURE_TAG_TARGET: resolvedTagTarget,
      PATH: `${fixtureBin}${path.delimiter}${process.env.PATH}`,
    },
    head,
  };
}

function runPackageFixture(fixture, extraArguments = []) {
  return spawnSync(
    path.join(fixture.fixtureRepository, "scripts", "package-semantic-release.sh"),
    ["0.1.1", fixture.head, ...extraArguments],
    {
      cwd: fixture.fixtureRepository,
      encoding: "utf8",
      env: fixture.environment,
    },
  );
}

function loadReleaseConfig(repositoryOverride) {
  const environmentName = "TAPZIQ_RELEASE_REPOSITORY";
  const previousValue = process.env[environmentName];
  const previouslyPresent = Object.hasOwn(process.env, environmentName);
  if (repositoryOverride === undefined) {
    delete process.env[environmentName];
  } else {
    process.env[environmentName] = repositoryOverride;
  }
  delete require.cache[require.resolve(releaseConfigPath)];

  try {
    return require(releaseConfigPath);
  } finally {
    delete require.cache[require.resolve(releaseConfigPath)];
    if (previouslyPresent) {
      process.env[environmentName] = previousValue;
    } else {
      delete process.env[environmentName];
    }
  }
}

const releaseConfig = loadReleaseConfig();
const pluginEntries = new Map(
  releaseConfig.plugins.map((plugin) => [plugin[0], plugin[1]]),
);
const analyzerOptions = pluginEntries.get("@semantic-release/commit-analyzer");
const notesOptions = pluginEntries.get(
  "@semantic-release/release-notes-generator",
);

async function releaseType(message) {
  const { analyzeCommits } = await import("@semantic-release/commit-analyzer");
  return analyzeCommits(
    analyzerOptions,
    {
      commits: [{ hash: "0123456789abcdef", message }],
      cwd: repositoryRoot,
      logger: { log() {} },
    },
  );
}

async function releaseNotes(messages) {
  const { generateNotes } = await import(
    "@semantic-release/release-notes-generator"
  );
  const commits = messages.map((message, index) => ({
    hash: `${index + 1}`.padStart(40, "0"),
    message,
  }));
  return generateNotes(
    notesOptions,
    {
      commits,
      cwd: repositoryRoot,
      lastRelease: { gitHead: "0".repeat(40), gitTag: "v0.1.0" },
      nextRelease: {
        gitHead: commits.at(-1).hash,
        gitTag: "v0.2.0",
        version: "0.2.0",
      },
      options: {
        repositoryUrl: "https://github.com/tapziq/Tapziq.git",
      },
    },
  );
}

test("release repository override uses a canonical HTTPS URL", () => {
  assert.equal(
    loadReleaseConfig("Renamed-Org/Tapziq.App_2").repositoryUrl,
    "https://github.com/Renamed-Org/Tapziq.App_2.git",
  );
  assert.equal(Object.hasOwn(releaseConfig, "repositoryUrl"), false);
});

test("invalid repository overrides are rejected", () => {
  for (const repository of [
    "",
    "Tapziq",
    "Tapziq/Tapziq/extra",
    "Tapziq/..",
    "Tapziq/Tap ziq",
    "https://github.com/tapziq/Tapziq",
    "tapziq/Tapziq\nINJECTED=value",
  ]) {
    assert.throws(
      () => loadReleaseConfig(repository),
      /must be a canonical owner\/repository name/,
      repository,
    );
  }
});

test("release configuration packages before publishing exact assets", () => {
  assert.deepEqual(releaseConfig.branches, ["main"]);
  assert.equal(releaseConfig.tagFormat, "v${version}");
  assert.deepEqual(
    releaseConfig.plugins.map(([plugin]) => plugin),
    [
      "@semantic-release/commit-analyzer",
      "@semantic-release/release-notes-generator",
      "@semantic-release/exec",
      "@semantic-release/github",
    ],
  );
  assert.deepEqual(notesOptions, analyzerOptions);
  assert.equal(
    pluginEntries.get("@semantic-release/exec").prepareCmd,
    "./scripts/package-semantic-release.sh "
      + "${nextRelease.version} ${nextRelease.gitHead}",
  );

  const githubOptions = pluginEntries.get("@semantic-release/github");
  assert.deepEqual(
    githubOptions.assets.map(({ path: assetPath }) => assetPath),
    [
      "dist/release/Tapziq-v*.apk",
      "dist/release/SHA256SUMS",
      "dist/release/LICENSE.txt",
      "dist/release/THIRD_PARTY_NOTICES.md",
    ],
  );
  assert.equal(githubOptions.releaseNameTemplate,
    "Tapziq Keyboard <%= nextRelease.version %>");
  assert.match(githubOptions.releaseBodyTemplate, /<%= nextRelease\.notes %>/);
  assert.equal(githubOptions.draftRelease, false);
  assert.equal(githubOptions.successComment, false);
  assert.equal(githubOptions.failComment, false);
  assert.equal(githubOptions.releasedLabels, false);
});

test("Conventional Commits map to intended SemVer levels", async () => {
  assert.equal(await releaseType("fix: repair shift state"), "patch");
  assert.equal(await releaseType("perf: reduce keyboard startup work"), "patch");
  assert.equal(await releaseType("feat: add a layout"), "minor");
  assert.equal(await releaseType("feat!: replace the layout contract"), "major");
  assert.equal(
    await releaseType(
      "chore: reorganize code\n\nBREAKING CHANGE: remove the old contract",
    ),
    "major",
  );
});

test("non-product commits do not publish releases", async () => {
  for (const message of [
    "build: update Gradle",
    "chore: maintain dependencies",
    "ci: configure automation",
    "docs: clarify installation",
    "refactor: reorganize helpers",
    "style: format sources",
    "test: cover layouts",
  ]) {
    assert.equal(await releaseType(message), null, message);
  }
});

test("generated release notes include user-visible changes", async () => {
  const notes = await releaseNotes([
    "feat(layout): add a compact layout",
    "fix(shift): preserve one-shot state",
    "docs: clarify installation",
  ]);
  assert.match(notes, /### Features/);
  assert.match(notes, /add a compact layout/);
  assert.match(notes, /### Bug Fixes/);
  assert.match(notes, /preserve one-shot state/);
  assert.doesNotMatch(notes, /clarify installation/);
});

test("semantic versions map to monotonic Android version codes", () => {
  const cases = new Map([
    ["0.0.1", "1"],
    ["0.1.1", "1001"],
    ["0.2.0", "2000"],
    ["1.0.0", "1000000"],
    ["1.2.3", "1002003"],
    ["2100.0.0", "2100000000"],
  ]);
  for (const [version, expected] of cases) {
    assert.equal(
      execFileSync(versionCodeScript, [version], { encoding: "utf8" }).trim(),
      expected,
      version,
    );
  }
});

test("invalid or unrepresentable semantic versions are rejected", () => {
  for (const version of [
    "0.0.0",
    "0.1",
    "v0.1.1",
    "0.1.1-beta.1",
    "00.1.1",
    "0.1000.0",
    "0.0.1000",
    "2100.0.1",
    "18446744073709551617.0.0",
    "999999999999999999999999999999999999999999999.0.0",
  ]) {
    assert.throws(
      () => execFileSync(versionCodeScript, [version], { stdio: "pipe" }),
      undefined,
      version,
    );
  }
});

test("package reconciliation accepts only the exact existing release tag", (t) => {
  for (const [fixtureOptions, arguments_, expectedStatus, expectedError] of [
    [{}, [], 1, /Release tag already exists/],
    [{}, ["--allow-existing-tag"], 0, null],
    [{ tagTarget: "other" }, ["--allow-existing-tag"], 1,
      /does not resolve to EXPECTED_SOURCE_COMMIT/],
    [{ tagExists: false }, ["--allow-existing-tag"], 1,
      /requires an existing local release tag/],
    [{}, ["--reconcile-existing-tag"], 1, /Usage:/],
  ]) {
    const fixture = createPackageFixture(fixtureOptions);
    t.after(() => rmSync(fixture.fixtureRoot, { recursive: true, force: true }));
    const result = runPackageFixture(fixture, arguments_);
    assert.equal(result.status, expectedStatus, result.stderr);
    if (expectedError !== null) {
      assert.match(result.stderr, expectedError);
    }
  }
});

test("packaging refuses ignored output symlinks before invoking the build", (t) => {
  for (const outputSymlink of ["dist", "release"]) {
    const fixture = createPackageFixture({ outputSymlink });
    t.after(() => rmSync(fixture.fixtureRoot, { recursive: true, force: true }));
    const result = runPackageFixture(fixture, ["--allow-existing-tag"]);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /must not be symbolic links/);
    assert.equal(
      readFileSync(path.join(fixture.fixtureRoot, "outside", "sentinel"), "utf8"),
      "keep\n",
    );
  }
});

test("published verification supports clean reruns and bounded backoff", () => {
  assert.match(
    publishedVerifierScript,
    /No local packaged release is present; verifying downloaded assets independently/,
  );
  assert.match(
    publishedVerifierScript,
    /verify-release-assets\.sh"[\s\S]*?"\$local_release_directory"/,
  );
  assert.match(publishedVerifierScript, /poll_delay_seconds=3/);
  assert.match(publishedVerifierScript, /poll_delay_seconds < 30/);
  assert.match(publishedVerifierScript, /poll_delay_seconds=30/);
  assert.doesNotMatch(publishedVerifierScript, /sleep [6-9][0-9]/);
});

test("APK verification accepts portable SHA-256 tools and requires unzip", () => {
  assert.match(apkVerifierScript, /command -v unzip/);
  assert.match(apkVerifierScript, /command -v sha256sum/);
  assert.match(apkVerifierScript, /command -v shasum/);
  assert.match(apkVerifierScript, /apk_sha256="\$\(sha256sum/);
  assert.match(apkVerifierScript, /apk_sha256="\$\(shasum -a 256/);
});

test("production smoke test installs and types through the selected IME", () => {
  assert.match(emulatorSmokeScript, /adb uninstall "\$package_name"/);
  assert.match(emulatorSmokeScript, /adb install --no-streaming/);
  assert.match(emulatorSmokeScript, /adb shell ime enable/);
  assert.match(emulatorSmokeScript, /adb shell ime set/);
  assert.match(emulatorSmokeScript, /mSelectedMethodId=\$ime_component/);
  assert.match(emulatorSmokeScript, /mInputShown=true/);
  assert.match(emulatorSmokeScript, /dumpsys window windows/);
  assert.match(emulatorSmokeScript, /adb shell input tap "\$key_x" "\$key_y"/);
  assert.match(emulatorSmokeScript, /typed_text/);
});

test("workflow keeps secrets out of PR verification and uses safe token scopes", () => {
  assert.match(workflow, /\npermissions: \{\}/);
  assert.match(workflow, /\n  verify:[\s\S]*?\n    permissions:\n      contents: read/);
  assert.match(
    workflow,
    /\n  release:[\s\S]*?\n    permissions:\n      attestations: read\n      contents: write/,
  );
  assert.match(workflow, /environment: production/);
  assert.equal((workflow.match(/fetch-depth: 0/g) || []).length, 2);
  assert.equal((workflow.match(/persist-credentials: false/g) || []).length, 2);
  assert.match(
    workflow,
    /Verify Conventional Commit history\n        run: npm run check:commits/,
  );
  const preflightStep = workflow.match(
    /      - name: Verify production release controls\n[\s\S]*?(?=\n      - name: Set up JDK 17)/,
  );
  assert(preflightStep);
  assert.doesNotMatch(preflightStep[0], /GH_TOKEN:/);

  const publishStep = workflow.match(
    /      - name: Reconcile, build, smoke-test, and publish\n[\s\S]*?(?=\n      - name: Verify any release for this commit)/,
  );
  assert(publishStep);
  assert.match(
    publishStep[0],
    /\n          GITHUB_TOKEN: \$\{\{ secrets\.GITHUB_TOKEN \}\}/,
  );
  assert.match(
    publishStep[0],
    /\n          GH_TOKEN: \$\{\{ secrets\.GITHUB_TOKEN \}\}/,
  );
  assert.match(publishStep[0], /TAPZIQ_RUN_EMULATOR_SMOKE: "1"/);
  assert.match(publishStep[0], /node scripts\/reconcile-interrupted-release\.cjs/);
  assert.match(publishStep[0], /npm run release/);
  assert.match(
    workflow,
    /Enable KVM for the Android emulator[\s\S]*99-kvm4all\.rules[\s\S]*udevadm trigger --name-match=kvm[\s\S]*test -r \/dev\/kvm[\s\S]*test -w \/dev\/kvm/,
  );
  assert.match(publishStep[0], /disable-linux-hw-accel: false/);
  assert.match(publishStep[0], /emulator-boot-timeout: 600/);
  assert.match(publishStep[0], /emulator-options: .* -no-metrics/);
  assert.match(
    publishStep[0],
    /ReactiveCircus\/android-emulator-runner@[0-9a-f]{40}/,
  );

  const actionReferences = [...workflow.matchAll(/uses: ([^\s]+)/g)]
    .map((match) => match[1]);
  assert.equal(actionReferences.length, 11);
  for (const actionReference of actionReferences) {
    assert.match(
      actionReference,
      /^[^@\s]+@[0-9a-f]{40}$/,
      actionReference,
    );
  }
});

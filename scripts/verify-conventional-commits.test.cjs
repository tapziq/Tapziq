"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const {
  compareStableTags,
  isAcceptedCommit,
  isConventionalCommit,
  isTemporarilyWaivedCommit,
  parseGitLog,
  selectLatestStableTag,
} = require("./verify-conventional-commits.cjs");

const waivedBadgeCommit = {
  hash: "6c8f38a99dd1debaaf33311c828bf3af139cc869",
  message: "Add CI badge to README for release verification\n",
};

test("latest stable tag ignores prereleases and compares numeric components", () => {
  assert.equal(
    selectLatestStableTag([
      "v1.9.9",
      "v1.10.0",
      "v2.0.0-beta.1",
      "not-a-release",
      "v0.1.0",
    ]),
    "v1.10.0",
  );
  assert(compareStableTags("v2.0.0", "v1.999.999") > 0);
});

test("Conventional Commit headers accept release and no-release types", () => {
  for (const message of [
    "fix: repair shift state",
    "feat(layout): add a compact layout",
    "feat!: replace the layout contract",
    "ci(release): activate automated APK releases",
    "docs: explain installation\n\nMore detail.",
  ]) {
    assert.equal(isConventionalCommit(message), true, message);
  }
});

test("malformed commit headers are rejected", () => {
  for (const message of [
    "Add a compact layout",
    "Feat: use an uppercase type",
    "fix missing colon",
    "fix: ",
    "feat(): empty scope",
    "feat(nested(scope)): invalid scope",
  ]) {
    assert.equal(isConventionalCommit(message), false, message);
  }
});

test("temporary waiver requires the exact commit, header, and release window", () => {
  assert.equal(
    isTemporarilyWaivedCommit(waivedBadgeCommit, "v0.1.1"),
    true,
  );
  assert.equal(
    isTemporarilyWaivedCommit({
      ...waivedBadgeCommit,
      hash: waivedBadgeCommit.hash.slice(0, 12),
    }, "v0.1.1"),
    false,
  );
  assert.equal(
    isTemporarilyWaivedCommit({
      ...waivedBadgeCommit,
      hash: "f".repeat(40),
    }, "v0.1.1"),
    false,
  );
  assert.equal(
    isTemporarilyWaivedCommit({
      ...waivedBadgeCommit,
      message: "Add a different badge to README\n",
    }, "v0.1.1"),
    false,
  );
  assert.equal(
    isTemporarilyWaivedCommit(waivedBadgeCommit, "v0.2.0"),
    false,
  );
});

test("accepted commits include normal Conventional Commits", () => {
  assert.equal(isAcceptedCommit(waivedBadgeCommit, "v0.1.1"), true);
  assert.equal(isAcceptedCommit({
    hash: "f".repeat(40),
    message: "ci(release): recover automated releases\n",
  }, "v0.1.1"), true);
  assert.equal(isAcceptedCommit({
    ...waivedBadgeCommit,
    hash: "f".repeat(40),
  }, "v0.1.1"), false);
});

test("NUL-delimited Git log records preserve multiline messages", () => {
  assert.deepEqual(
    parseGitLog("a".repeat(40) + "\0fix: one\n\0" + "b".repeat(40)
      + "\0feat: two\n\nBody\n\0"),
    [
      { hash: "a".repeat(40), message: "fix: one\n" },
      { hash: "b".repeat(40), message: "feat: two\n\nBody\n" },
    ],
  );
});

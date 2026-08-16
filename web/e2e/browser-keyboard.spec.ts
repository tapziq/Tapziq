import { expect, test } from "@playwright/test";

test("previews, dismisses, applies, and rejects stale suggestions", async ({ page }) => {
  await page.goto("/?test-model=1");

  const editor = page.getByRole("textbox", { name: "Type or paste text" });
  const proofread = page.getByRole("button", { name: "Proofread with browser Gemma 4" });
  await editor.fill("thiss is bad grammer.");
  await expect(proofread).toBeEnabled();
  const controlsDisabled = await page.evaluate(() => {
    document.querySelector<HTMLButtonElement>("#proofread-button")?.click();
    return ["download-button", "remove-button"].map((id) =>
      document.querySelector<HTMLButtonElement>(`#${id}`)?.disabled);
  });
  expect(controlsDisabled).toEqual([true, true]);
  await expect(page.getByText("Gemma suggests")).toBeVisible();
  await expect(page.locator("#preview-text")).toHaveText("This is bad grammar.");
  await page.getByRole("button", { name: "Dismiss" }).click();
  await expect(editor).toHaveValue("thiss is bad grammer.");

  await proofread.click();
  await page.getByRole("button", { name: "Apply" }).click();
  await expect(editor).toHaveValue("This is bad grammar.");

  await editor.fill("teh second example");
  await proofread.click();
  await expect(page.getByText("Gemma suggests")).toBeVisible();
  await editor.fill("teh second example changed");
  await expect(page.locator("#preview")).toHaveClass(/stale/);
  await expect(page.getByRole("button", { name: "Apply" })).toBeDisabled();
  await expect(editor).toHaveValue("teh second example changed");

  await page.getByRole("button", { name: "Dismiss" }).click();
  await page.evaluate(() => {
    const button = document.querySelector<HTMLButtonElement>("#proofread-button");
    const field = document.querySelector<HTMLTextAreaElement>("#editor");
    if (button === null || field === null) {
      throw new Error("Test editor controls are missing.");
    }
    button.click();
    field.value = "teh text changed while inference was active";
    field.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText" }));
  });
  await expect(page.locator("#status")).toContainText(
    "editor changed while Gemma was running",
  );
  await expect(page.locator("#preview")).toBeHidden();
  await expect(editor).toHaveValue("teh text changed while inference was active");
});

test("uses only same-origin resources and launches from the service worker offline", async ({
  context,
  page,
}) => {
  const requestOrigins = new Set<string>();
  page.on("request", (request) => requestOrigins.add(new URL(request.url()).origin));

  await page.goto("/?test-model=1");
  await expect(page.getByRole("heading", { name: "Tapziq Keyboard" })).toBeVisible();
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null))
    .toBe(true);

  expect([...requestOrigins]).toEqual(["http://127.0.0.1:4173"]);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Tapziq Keyboard" })).toBeVisible();
  await page.getByRole("textbox", { name: "Type or paste text" }).fill("teh offline example");
  await expect(page.getByRole("button", { name: "Proofread with browser Gemma 4" }))
    .toBeEnabled();
  await page.getByRole("link", { name: "Browser privacy" }).click();
  await expect(page.getByRole("heading", { name: /Browser privacy/i })).toBeVisible();
});

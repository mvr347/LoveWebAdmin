const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const modulePromise = import(
  "data:text/javascript;base64," +
    fs
      .readFileSync("src/main/resources/web/js/core/form-validation.js")
      .toString("base64")
);
test("new password rules match server length and both letter cases", async () => {
  const { passwordError } = await modulePromise;
  assert.ok(passwordError("Short12!"));
  assert.ok(passwordError("onlylower123!"));
  assert.ok(passwordError("ONLYUPPER123!"));
  assert.ok(passwordError("NoDigitsHere!"));
  assert.ok(passwordError("NoSpecial1234"));
  assert.equal(passwordError("StrongPassword123!"), "");
  assert.ok(passwordError("StrongPassword123!", 24));
  assert.equal(passwordError("EvenLongerStrongPassword123!", 24), "");
});
test("usernames are checked before requesting availability", async () => {
  const { usernameError } = await modulePromise;
  assert.ok(usernameError("admin", { onboarding: true }));
  assert.ok(usernameError("неправильно"));
  assert.ok(usernameError("bad-name"));
  assert.ok(usernameError("ab"));
  assert.ok(usernameError("a".repeat(17), { minecraft: true }));
  assert.equal(usernameError("Ada123", { minecraft: true }), "");
  assert.equal(usernameError("NewPersonalName", { onboarding: true }), "");
});

test("TOTP and backup formats are checked before confirmation", async () => {
  const { confirmationCodeError } = await modulePromise;
  assert.equal(confirmationCodeError("123-456"), "");
  assert.ok(confirmationCodeError("123"));
  assert.ok(confirmationCodeError("ABCDEF"));
  assert.equal(confirmationCodeError("ABCD-1234", true), "");
  assert.equal(confirmationCodeError("1234-5678", true), "");
  assert.ok(confirmationCodeError("ABCD-1234", false));
});

test("duration limits reject malformed input before submitting a punishment", async () => {
  const { durationError } = await modulePromise;
  assert.equal(durationError("30m"), "");
  assert.equal(durationError("permanent"), "");
  assert.ok(durationError("0m"));
  assert.ok(durationError("abc"));
  assert.ok(durationError("1s", 60));
  assert.ok(durationError("999999999w"));
});

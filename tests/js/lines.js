// For the table test (tests/mynx/test_mynx_js.py): reads a JSON list of
// JavaScript calls on stdin ("mynx.location({gps: true})"), runs each
// against a fresh mynx.js and a fake port, and prints a JSON list of
// what each sent: [CALL, LINE…], or {"error": MESSAGE} when it threw.
'use strict';
const { load } = require('./harness');

async function linesOf(expression) {
  const page = load();
  const port = page.port();
  const run = new Function('mynx', `return (${expression});`);
  let failed = null;
  try {
    const result = run(page.mynx);
    if (result && typeof result.catch === 'function') result.catch((e) => { failed = e; });
    if (result && result.done && typeof result.done.catch === 'function') result.done.catch(() => {});
  } catch (e) {
    return { error: e.message };
  }
  const sent = await Promise.race([port.call(), new Promise((resolve) => setTimeout(resolve, 500))]);
  if (!sent) return { error: failed ? failed.message : 'nothing sent' };
  return [sent.call, ...sent.args];
}

(async () => {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const out = [];
  for (const expression of JSON.parse(input)) out.push(await linesOf(expression));
  process.stdout.write(JSON.stringify(out) + '\n');
  process.exit(0);
})();

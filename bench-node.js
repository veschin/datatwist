const { execSync, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function nativeEval(expr) {
  // For multiline: write to temp file and use `run`
  if (expr.includes('\n')) {
    const tmp = path.join(os.tmpdir(), 'dt-bench.dt');
    fs.writeFileSync(tmp, expr);
    return execFileSync('./datatwist', ['run', tmp]);
  }
  return execFileSync('./datatwist', ['eval', '-e', expr]);
}

function bench(label, dtExpr, jsFn, runs = 10) {
  console.log(`\n### ${label}`);

  // warmup
  for (let i = 0; i < 3; i++) { jsFn(); }
  for (let i = 0; i < 3; i++) { nativeEval(dtExpr); }

  // Node.js
  let start = process.hrtime.bigint();
  for (let i = 0; i < runs; i++) { jsFn(); }
  let ms = Number(process.hrtime.bigint() - start) / 1e6;
  console.log(`  Node.js:    ${ms.toFixed(1)} ms total, ${(ms / runs).toFixed(2)} ms/run`);

  // DataTwist native
  start = process.hrtime.bigint();
  for (let i = 0; i < runs; i++) { nativeEval(dtExpr); }
  ms = Number(process.hrtime.bigint() - start) / 1e6;
  console.log(`  DT native:  ${ms.toFixed(1)} ms total, ${(ms / runs).toFixed(2)} ms/run  (~${((ms / runs) - 5).toFixed(1)} ms without startup)`);
}

console.log('=== DataTwist Native vs Node.js ===');
console.log('(Node: in-process timing. DT: fork+exec per run, ~5ms startup overhead)');

bench('Arithmetic',
  '1 + 2 * 3 - 4 / 2 + 10 % 3',
  () => 1 + 2 * 3 - 4 / 2 + 10 % 3);

bench('Range 10K',
  'range 1 10000',
  () => Array.from({ length: 10000 }, (_, i) => i + 1));

bench('Map 10K',
  'range 1 10000 |> map [x -> x * 2]',
  () => Array.from({ length: 10000 }, (_, i) => i + 1).map(x => x * 2));

bench('Filter 10K',
  'range 1 10000 |> filter [x -> x % 2 = 0]',
  () => Array.from({ length: 10000 }, (_, i) => i + 1).filter(x => x % 2 === 0));

bench('Reduce 10K',
  'range 1 10000 |> reduce [a b -> a + b] 0',
  () => Array.from({ length: 10000 }, (_, i) => i + 1).reduce((a, b) => a + b, 0));

bench('Sort 10K',
  'range 1 10000 |> reverse |> sort',
  () => Array.from({ length: 10000 }, (_, i) => i + 1).reverse().sort((a, b) => a - b));

bench('Chained 10K (map+filter+reduce)',
  'range 1 10000 |> map [x -> x * 2] |> filter [x -> x > 5000] |> reduce [a b -> a + b] 0',
  () => Array.from({ length: 10000 }, (_, i) => i + 1)
    .map(x => x * 2).filter(x => x > 5000).reduce((a, b) => a + b, 0));

bench('10K objects |> filter |> map',
  'range 1 10000 |> map [i -> {id: i name: "user" age: i % 80}] |> filter [u -> u.age > 60] |> map [u -> u.id]',
  () => Array.from({ length: 10000 }, (_, i) => ({ id: i+1, name: 'user', age: (i+1) % 80 }))
    .filter(u => u.age > 60).map(u => u.id));

bench('Closures 10K',
  'add is [x -> [y -> x + y]]\nplus5 is add 5\nrange 1 10000 |> map plus5',
  () => {
    const add = x => y => x + y;
    const plus5 = add(5);
    return Array.from({ length: 10000 }, (_, i) => i + 1).map(plus5);
  });

bench('Guards 10K',
  'classify is [x ->\n  | x > 75 -> "high"\n  | x > 25 -> "mid"\n  | true -> "low"\n]\nrange 1 10000 |> map [i -> i % 100] |> map classify',
  () => {
    const classify = x => x > 75 ? 'high' : x > 25 ? 'mid' : 'low';
    return Array.from({ length: 10000 }, (_, i) => i + 1).map(i => i % 100).map(classify);
  });

bench('Nested 100x100',
  'range 1 100 |> map [x -> range 1 100 |> map [y -> x * y] |> reduce [a b -> a + b] 0]',
  () => Array.from({ length: 100 }, (_, i) => i + 1)
    .map(x => Array.from({ length: 100 }, (_, j) => j + 1)
      .map(y => x * y).reduce((a, b) => a + b, 0)));

console.log('\n=== Done ===');

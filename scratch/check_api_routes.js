const fs = require('fs');

const appJs = fs.readFileSync('src/main/resources/web/app.js', 'utf8');

// Match all api('METHOD', 'PATH'...) or api("METHOD", "PATH"...)
const apiRegex = /api\(\s*['"]([A-Z]+)['"]\s*,\s*`?(['"][^'"]+['"]|`[^`]+`)/g;
let m;
const calls = [];
while ((m = apiRegex.exec(appJs)) !== null) {
    const method = m[1];
    let path = m[2];
    calls.push({ method, path });
}

console.log(`Found ${calls.length} API calls in app.js`);
const uniquePaths = new Map();
calls.forEach(c => {
    const key = `${c.method} ${c.path}`;
    uniquePaths.set(key, (uniquePaths.get(key) || 0) + 1);
});

for (const [k, count] of uniquePaths.entries()) {
    console.log(`${k} (x${count})`);
}

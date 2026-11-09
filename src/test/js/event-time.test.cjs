const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');
const modulePromise=import('data:text/javascript,'+encodeURIComponent(fs.readFileSync('src/main/resources/web/js/core/event-time.js','utf8')));
test('event dates accept seconds and milliseconds without producing year 58826',async()=>{
 const {epochMillis,formatEventTime}=await modulePromise;
 assert.equal(epochMillis(1794220000),epochMillis(1794220000000));assert.equal(new Date(epochMillis(1794220000000)).getUTCFullYear(),2026);assert.equal(formatEventTime(1794220000),formatEventTime(1794220000000));assert.equal(epochMillis(Infinity),null);assert.equal(epochMillis(-1),null);
});
test('VL is rounded and never exposed as a confidence percentage',async()=>{const {formatViolationLevel}=await modulePromise;assert.equal(formatViolationLevel(3.5399105522379104),'3,54');assert.equal(formatViolationLevel(4),'4');assert.equal(formatViolationLevel('bad'),'—');});

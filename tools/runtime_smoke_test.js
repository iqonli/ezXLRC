const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const crypto = require("node:crypto");

let completed = false;
let failure = null;
const added = [];
const serviceData = new Map();

globalThis.AndroidBridge = {
  requestSync(raw) {
    const request = JSON.parse(raw);
    return JSON.stringify({
      statusCode: 200,
      headers: { "content-type": "text/plain; charset=utf-8" },
      body: request.url.endsWith("/raw") ? "" : "lyrics response",
      bodyBase64: request.url.endsWith("/raw") ? Buffer.from("raw lyrics").toString("base64") : undefined
    });
  },
  addLyric(raw) { added.push(JSON.parse(raw)); },
  getSvcData(key) { return serviceData.get(key) || ""; },
  setSvcData(key, value) { serviceData.set(key, value); },
  aesEcbEncrypt(plain, key) {
    const cipher = crypto.createCipheriv("aes-128-ecb", Buffer.from(key, "latin1"), null);
    return Buffer.concat([cipher.update(Buffer.from(plain, "utf8")), cipher.final()]).toString("hex");
  },
  md5Base64(value) {
    return crypto.createHash("md5").update(Buffer.from(value, "base64")).digest("hex");
  },
  parseHtml() {
    return JSON.stringify([{
      type: "element", tagName: "p", attributes: [],
      children: [{ type: "text", content: "parsed html" }]
    }]);
  },
  parseXml() {
    return JSON.stringify({ children: [{
      type: "element", tagName: "songinfo",
      attributes: [{ key: "id", value: "42" }],
      children: [{ type: "element", tagName: "name", attributes: [], children: [
        { type: "text", content: "XML song" }
      ] }]
    }] });
  },
  onSearchDone() { completed = true; },
  onSearchError(message) { failure = String(message); },
  log() {}
};

const runtime = fs.readFileSync("app/src/main/assets/js/runtime.js", "utf8");
vm.runInThisContext(runtime, { filename: "ezxlrc-runtime.js" });

assert.equal(globalThis.querystring.stringify({ a: "hello world", b: 2 }), "a=hello%20world&b=2");
assert.deepEqual(globalThis.querystring.parse("a=1&a=2"), { a: ["1", "2"] });
assert.equal(globalThis.atob(globalThis.btoa("abc")), "abc");
assert.equal(globalThis.CryptoES.MD5("abc").toString(), "900150983cd24fb0d6963f7d28e17f72");

const key = globalThis.CryptoES.enc.Latin1.parse("1234567890123456");
const encrypted = globalThis.CryptoES.AES.encrypt("ezXLRC", key, { mode: globalThis.CryptoES.mode.ECB });
assert.match(encrypted.ciphertext.toString(), /^[0-9a-f]+$/);

const html = globalThis.himalayaParse("<p>ignored by native mock</p>");
assert.equal(html[0].tagName, "p");
const xml = globalThis.mxml.loadString("<ignored/>");
assert.equal(xml.findElement("songinfo")[0].getAttr("id"), "42");
assert.equal(globalThis.getChildElementCDATA(xml.findElement("songinfo")[0], "name"), "XML song");

let requestBody = "";
globalThis.request({ url: "https://example.invalid/text" }, (_error, response, body) => {
  assert.equal(response.statusCode, 200);
  requestBody = body;
});
assert.equal(requestBody, "lyrics response");
const raw = globalThis.request({ url: "https://example.invalid/raw", raw: true });
assert.equal(Buffer.from(raw).toString("utf8"), "raw lyrics");

const script = `
import crypto from 'crypto-es/lib/index.js';
import { parse } from 'himalaya/src/index.js';
export function getLyrics(meta, manager) {
  request({ url: 'https://example.invalid/text' }, (err, response, body) => {
    if (err) throw new Error(err);
    const digest = crypto.MD5(crypto.enc.Utf8.parse('abc')).toString();
    const nodes = parse('<p>lyrics</p>');
    const xmlDoc = mxml.loadString('<ignored/>');
    manager.setSvcData('digest', digest);
    manager.addLyric({
      title: meta.title,
      artist: meta.artist,
      lyricText: body + ' · ' + nodes[0].tagName + ' · ' + xmlDoc.findElement('songinfo')[0].getAttr('id')
    });
  });
}
`;

(async () => {
  await globalThis.runSearcher(script, { title: "Song", artist: "Artist" });
  assert.equal(failure, null);
  assert.equal(completed, true);
  assert.equal(added.length, 1);
  assert.equal(added[0].title, "Song");
  assert.equal(added[0].lyricText, "lyrics response · p · 42");
  assert.equal(globalThis.AndroidBridge.getSvcData("digest"), "900150983cd24fb0d6963f7d28e17f72");
  process.stdout.write("QuickJS-compatible runtime bridge smoke test passed.\n");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

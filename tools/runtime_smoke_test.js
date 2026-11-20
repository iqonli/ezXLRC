'use strict';

const fs = require('node:fs');
const path = require('node:path');
const nodeCrypto = require('node:crypto');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/engine.html'), 'utf8');
const match = html.match(/<script>([\s\S]*?)<\/script>/);
if (!match) throw new Error('Runtime script was not found');

global.window = globalThis;
const events = [];
global.AndroidBridge = {
    requestSync(requestJson) {
        const request = JSON.parse(requestJson);
        if (request.raw) {
            return JSON.stringify({ statusCode: 200, headers: {}, bodyBase64: Buffer.from([0, 1, 255]).toString('base64') });
        }
        return JSON.stringify({ statusCode: 200, headers: {}, body: '[00:01.00]hello' });
    },
    addLyric(value) { events.push({ type: 'lyric', value: JSON.parse(value) }); },
    onSearchDone() { events.push({ type: 'done' }); },
    onSearchError(message) { events.push({ type: 'error', message }); },
    aesEcbEncrypt(plainText, keyText) {
        const key = Buffer.from(keyText, 'latin1');
        const cipher = nodeCrypto.createCipheriv(`aes-${key.length * 8}-ecb`, key, null);
        return Buffer.concat([cipher.update(plainText, 'utf8'), cipher.final()]).toString('hex');
    },
    md5Base64(bytesBase64) {
        return nodeCrypto.createHash('md5').update(Buffer.from(bytesBase64, 'base64')).digest('hex');
    },
    getSvcData() { return ''; },
    setSvcData() {},
    log() {},
};

eval(match[1]);

(async () => {
    const script = `
        export function getLyrics(meta, man) {
            request({ url: 'https://example.invalid/lyrics' }, (error, response, body) => {
                if (error || response.statusCode !== 200) throw new Error('request bridge failed');
                const item = man.createLyric();
                item.lyricText = body;
                man.addLyric(item);
            });
            const raw = request({ url: 'https://example.invalid/raw', raw: true });
            if (raw.length !== 3 || raw[2] !== 255) throw new Error('binary response bridge failed');
            Promise.resolve().then(() => {
                const item = man.createLyric();
                item.lyricText = 'promise-result';
                man.addLyric(item);
            });
            messageLoop(0);
        }
    `;
    await window.runSearcher(script, { title: 'Test', artist: 'Artist' });
    const lyrics = events.filter(event => event.type === 'lyric');
    if (lyrics.length !== 2) throw new Error(`Expected two lyrics, got ${lyrics.length}: ${JSON.stringify(events)}`);
    if (events.at(-1)?.type !== 'done') throw new Error(`Search did not finish: ${JSON.stringify(events)}`);
    if (lyrics[0].value.lyricText !== '[00:01.00]hello') throw new Error('Text request bridge returned wrong body');
    if (lyrics[1].value.lyricText !== 'promise-result') throw new Error('Promise completion bridge failed');

    const query = window.querystring.stringify({ q: 'hello world', n: 2 });
    if (query !== 'q=hello%20world&n=2') throw new Error(`querystring mismatch: ${query}`);
    const key = window.CryptoES.enc.Latin1.parse('1234567890123456');
    const plain = window.CryptoES.enc.Utf8.parse('test');
    const encrypted = window.CryptoES.AES.encrypt(plain, key, { mode: window.CryptoES.mode.ECB });
    if (encrypted.ciphertext.toString(window.CryptoES.enc.Hex) !== 'ddfbda2e0e480e5bdeb30b97ce155073') throw new Error('AES-128-ECB compatibility adapter failed');
    const digest = window.CryptoES.MD5(plain);
    const digestHex = digest.words.map(word => (word >>> 0).toString(16).padStart(8, '0')).join('').slice(0, digest.sigBytes * 2);
    if (digestHex !== '098f6bcd4621d373cade4e832627b4f6') throw new Error(`MD5 compatibility adapter failed: ${digestHex}`);
    console.log('ESLyric JavaScript bridge smoke test passed.');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});

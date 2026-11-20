(function (global) {
  "use strict";

  global.window = global;

  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  function bytesOf(value) {
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (Array.isArray(value)) return Uint8Array.from(value.map(v => Number(v) & 255));
    if (typeof value === "string") return utf8Bytes(value);
    return new Uint8Array(0);
  }

  function utf8Bytes(value) {
    const encoded = encodeURIComponent(String(value));
    const out = [];
    for (let i = 0; i < encoded.length; i++) {
      if (encoded[i] === "%") {
        out.push(parseInt(encoded.slice(i + 1, i + 3), 16));
        i += 2;
      } else {
        out.push(encoded.charCodeAt(i));
      }
    }
    return Uint8Array.from(out);
  }

  function utf8String(value) {
    const bytes = bytesOf(value);
    let encoded = "";
    for (let i = 0; i < bytes.length; i++) encoded += "%" + bytes[i].toString(16).padStart(2, "0");
    try { return decodeURIComponent(encoded); } catch (_) { return ""; }
  }

  function latin1Bytes(value) {
    const out = new Uint8Array(String(value).length);
    for (let i = 0; i < out.length; i++) out[i] = String(value).charCodeAt(i) & 255;
    return out;
  }

  function latin1String(value) {
    const bytes = bytesOf(value);
    let out = "";
    for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return out;
  }

  function base64Encode(value) {
    const bytes = bytesOf(value);
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i];
      const hasB = i + 1 < bytes.length;
      const hasC = i + 2 < bytes.length;
      const b = hasB ? bytes[i + 1] : 0;
      const c = hasC ? bytes[i + 2] : 0;
      out += alphabet[a >> 2];
      out += alphabet[((a & 3) << 4) | (b >> 4)];
      out += hasB ? alphabet[((b & 15) << 2) | (c >> 6)] : "=";
      out += hasC ? alphabet[c & 63] : "=";
    }
    return out;
  }

  function base64Decode(value) {
    const input = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
    const out = [];
    for (let i = 0; i < input.length; i += 4) {
      const a = alphabet.indexOf(input[i]);
      const b = alphabet.indexOf(input[i + 1]);
      const c = input[i + 2] === "=" ? -1 : alphabet.indexOf(input[i + 2]);
      const d = input[i + 3] === "=" ? -1 : alphabet.indexOf(input[i + 3]);
      if (a < 0 || b < 0) break;
      out.push((a << 2) | (b >> 4));
      if (c >= 0) out.push(((b & 15) << 4) | (c >> 2));
      if (d >= 0 && c >= 0) out.push(((c & 3) << 6) | d);
    }
    return Uint8Array.from(out);
  }

  global.base64Encode = base64Encode;
  global.base64Decode = base64Decode;

  function hexEncode(value) {
    return Array.from(bytesOf(value), b => b.toString(16).padStart(2, "0")).join("");
  }

  function hexDecode(value) {
    const text = String(value || "").replace(/[^0-9a-f]/gi, "");
    const out = [];
    for (let i = 0; i + 1 < text.length; i += 2) out.push(parseInt(text.slice(i, i + 2), 16));
    return Uint8Array.from(out);
  }

  function wordArray(value, text) {
    const bytes = bytesOf(value);
    const words = [];
    for (let i = 0; i < bytes.length; i += 4) {
      words.push(((bytes[i] || 0) << 24) | ((bytes[i + 1] || 0) << 16) | ((bytes[i + 2] || 0) << 8) | (bytes[i + 3] || 0));
    }
    return {
      __bytes: Array.from(bytes),
      __text: text === undefined ? null : text,
      words,
      sigBytes: bytes.length,
      toString(encoder) {
        if (encoder && typeof encoder.stringify === "function") return encoder.stringify(this);
        return hexEncode(bytes);
      }
    };
  }

  const CryptoES = {
    enc: {
      Utf8: {
        parse(value) { return wordArray(utf8Bytes(value), String(value)); },
        stringify(value) { return utf8String(value && value.__bytes ? value.__bytes : value); }
      },
      Latin1: {
        parse(value) { return wordArray(latin1Bytes(value), String(value)); },
        stringify(value) { return latin1String(value && value.__bytes ? value.__bytes : value); }
      },
      Hex: {
        stringify(value) { return hexEncode(value && value.__bytes ? value.__bytes : value); },
        parse(value) { return wordArray(hexDecode(value)); }
      },
      Base64: {
        stringify(value) { return base64Encode(value && value.__bytes ? value.__bytes : value); },
        parse(value) { return wordArray(base64Decode(value)); }
      }
    },
    lib: { WordArray: { create(value) { return wordArray(value); } } },
    mode: { ECB: "ECB" },
    pad: { Pkcs7: "Pkcs7" },
    MD5(value) {
      const bytes = typeof value === "string" ? utf8Bytes(value) : bytesOf(value && value.__bytes ? value.__bytes : value);
      const digest = global.AndroidBridge.md5Base64(base64Encode(bytes));
      return wordArray(hexDecode(digest));
    },
    AES: {
      encrypt(value, key) {
        const plainBytes = typeof value === "string" ? utf8Bytes(value) : bytesOf(value && value.__bytes ? value.__bytes : value);
        const keyText = key && key.__text !== null && key.__text !== undefined
          ? key.__text
          : latin1String(key && key.__bytes ? key.__bytes : key);
        const encryptedHex = global.AndroidBridge.aesEcbEncrypt(utf8String(plainBytes), String(keyText));
        const ciphertext = wordArray(hexDecode(encryptedHex));
        return { ciphertext, toString() { return encryptedHex; } };
      }
    }
  };
  global.CryptoES = CryptoES;

  global.querystring = {
    stringify(object) {
      if (!object || typeof object !== "object") return "";
      const pairs = [];
      Object.keys(object).forEach(key => {
        const value = object[key];
        if (value === undefined || value === null) return;
        const values = Array.isArray(value) ? value : [value];
        values.forEach(item => pairs.push(encodeURIComponent(key) + "=" + encodeURIComponent(String(item))));
      });
      return pairs.join("&");
    },
    parse(text) {
      const result = {};
      String(text || "").replace(/^\?/, "").split("&").forEach(part => {
        if (!part) return;
        const index = part.indexOf("=");
        const key = decodeURIComponent((index < 0 ? part : part.slice(0, index)).replace(/\+/g, " "));
        const value = decodeURIComponent((index < 0 ? "" : part.slice(index + 1)).replace(/\+/g, " "));
        if (Object.prototype.hasOwnProperty.call(result, key)) {
          if (!Array.isArray(result[key])) result[key] = [result[key]];
          result[key].push(value);
        } else result[key] = value;
      });
      return result;
    }
  };

  global.btoa = function (value) {
    return base64Encode(latin1Bytes(value));
  };
  global.atob = function (value) {
    return latin1String(base64Decode(value));
  };

  global.himalayaParse = function (html) {
    return JSON.parse(global.AndroidBridge.parseHtml(String(html || "")));
  };

  function findXmlElements(nodes, name, output) {
    (nodes || []).forEach(node => {
      if (!node || node.type !== "element") return;
      if (node.tagName === name) output.push(node);
      findXmlElements(node.children, name, output);
    });
    return output;
  }

  function wrapXmlNode(node) {
    return {
      getAttr(name) {
        const attributes = node.attributes || [];
        const attribute = attributes.find(item => item.key === name);
        return attribute ? attribute.value : null;
      },
      getFirstChild() {
        const child = (node.children || [])[0];
        return child ? { getCDATA() { return child.content || ""; } } : null;
      },
      findElement(name) {
        return wrapXmlNodes(findXmlElements(node.children, String(name), []));
      }
    };
  }

  function wrapXmlNodes(nodes) {
    const wrapped = (nodes || []).map(wrapXmlNode);
    wrapped.getAttr = name => wrapped.length ? wrapped[0].getAttr(name) : null;
    wrapped.getFirstChild = () => wrapped.length ? wrapped[0].getFirstChild() : null;
    return wrapped;
  }

  global.mxml = {
    MXML_DESCEND: 1,
    loadString(xml) {
      const parsed = JSON.parse(global.AndroidBridge.parseXml(String(xml || "")));
      if (!parsed || parsed.error) return null;
      return {
        findElement(name) {
          return wrapXmlNodes(findXmlElements(parsed.children, String(name), []));
        }
      };
    }
  };

  global.getChildElementCDATA = function (node, name) {
    if (!node || typeof node.findElement !== "function") return "";
    const child = node.findElement(name);
    if (!child) return "";
    const first = typeof child.getFirstChild === "function" ? child.getFirstChild() : null;
    return first && typeof first.getCDATA === "function" ? first.getCDATA() || "" : "";
  };

  global.evalLib = function () {};
  global.messageLoop = function () {};
  global.clearTimeout = function () {};
  global.setTimeout = function (callback) {
    if (typeof callback === "function") callback();
    return 0;
  };
  global.console = {
    log: (...args) => global.AndroidBridge.log(args.map(String).join(" ")),
    info: (...args) => global.AndroidBridge.log(args.map(String).join(" ")),
    warn: (...args) => global.AndroidBridge.log(args.map(String).join(" ")),
    error: (...args) => global.AndroidBridge.log(args.map(String).join(" "))
  };

  global.request = function (input, callback) {
    const settings = typeof input === "string" ? { url: input } : Object.assign({}, input || {});
    const raw = settings.raw === true || settings.responseType === "arraybuffer";
    const body = settings.body;
    const payload = {
      url: String(settings.url || ""),
      method: String(settings.method || "GET").toUpperCase(),
      timeout: Number(settings.timeout || 12000),
      headers: settings.headers || {},
      raw
    };
    if (body !== undefined && body !== null) {
      if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
        payload.bodyBase64 = base64Encode(body);
      } else {
        payload.body = typeof body === "string" ? body : JSON.stringify(body);
      }
    }
    const responseData = JSON.parse(global.AndroidBridge.requestSync(JSON.stringify(payload)));
    const response = {
      statusCode: Number(responseData.statusCode || 0),
      headers: responseData.headers || {}
    };
    const responseBody = responseData.bodyBase64
      ? (raw ? base64Decode(responseData.bodyBase64) : utf8String(base64Decode(responseData.bodyBase64)))
      : String(responseData.body || "");
    const error = responseData.error || null;
    if (typeof callback === "function") callback(error, response, responseBody);
    return responseBody;
  };

  function flushMicrotasks(turns) {
    if (turns <= 0) return Promise.resolve();
    return Promise.resolve().then(() => flushMicrotasks(turns - 1));
  }

  global.runSearcher = function (source, meta) {
    try {
      let code = String(source || "");
      code = code.replace(/^\s*import\s+crypto\s+from\s+['"]crypto-es\/lib\/index\.js['"]\s*;?\s*$/gm, "const crypto = window.CryptoES;");
      code = code.replace(/^\s*import\s*\{\s*parse\s*\}\s*from\s+['"]himalaya\/src\/index\.js['"]\s*;?\s*$/gm, "const parse = window.himalayaParse;");
      code = code.replace(/^\s*import\s+.*?from\s+['"][^'"]+['"]\s*;?\s*$/gm, "");
      code = code.replace(/\bexport\s+(?=(?:async\s+)?(?:function|const|let|class)\b)/g, "");

      const factory = new Function(
        "window", "request", "querystring", "mxml", "getChildElementCDATA", "messageLoop", "evalLib", "console",
        code + "\n;return { getLyrics: typeof getLyrics === 'function' ? getLyrics : null };"
      );
      const api = factory(global, global.request, global.querystring, global.mxml, global.getChildElementCDATA, global.messageLoop, global.evalLib, global.console);
      if (!api || typeof api.getLyrics !== "function") throw new Error("Searcher does not export getLyrics");

      const manager = {
        createLyric() { return {}; },
        addLyric(item) {
          const value = Object.assign({}, item || {});
          if (value.lyricData !== undefined && value.lyricData !== null) {
            value.lyricDataBase64 = base64Encode(value.lyricData);
            delete value.lyricData;
          }
          global.AndroidBridge.addLyric(JSON.stringify(value));
        },
        getSvcData(key) { return global.AndroidBridge.getSvcData(String(key)); },
        setSvcData(key, value) { global.AndroidBridge.setSvcData(String(key), String(value)); },
        checkAbort() { return false; }
      };

      const result = api.getLyrics(meta || {}, manager);
      return Promise.resolve(result)
        .then(() => flushMicrotasks(16))
        .then(() => global.AndroidBridge.onSearchDone())
        .catch(error => global.AndroidBridge.onSearchError(error && error.message ? error.message : String(error)));
    } catch (error) {
      global.AndroidBridge.onSearchError(error && error.message ? error.message : String(error));
      return Promise.resolve();
    }
  };
})(globalThis);

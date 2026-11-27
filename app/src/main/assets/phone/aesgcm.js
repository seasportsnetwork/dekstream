// Minimal AES-128-GCM encryption for the DekTV phone setup page.
// Phone browsers block WebCrypto on plain-http LAN pages, so this does it in script.
// Output format (shared with the app): base64url(iv[12] || ciphertext || tag[16]).
(function (root) {
  var S = [], X = [];
  (function init() {
    var p = 1, q = 1;
    var sbox = new Array(256);
    do {
      p = p ^ ((p << 1) & 0xff) ^ (p & 0x80 ? 0x1b : 0);
      q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
      if (q & 0x80) q ^= 0x09;
      var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
      sbox[p] = (x ^ 0x63) & 0xff;
    } while (p !== 1);
    sbox[0] = 0x63;
    S = sbox;
  })();
  function xt(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff; }

  function expand(key) {
    var w = key.slice(0), rc = 1;
    for (var i = 16; i < 176; i += 4) {
      var t = w.slice(i - 4, i);
      if (i % 16 === 0) {
        t = [S[t[1]] ^ rc, S[t[2]], S[t[3]], S[t[0]]];
        rc = xt(rc);
      }
      for (var j = 0; j < 4; j++) w[i + j] = w[i - 16 + j] ^ t[j];
    }
    return w;
  }

  function encBlock(w, b) {
    var s = b.slice(0), r, c, i;
    for (i = 0; i < 16; i++) s[i] ^= w[i];
    for (r = 1; r <= 10; r++) {
      for (i = 0; i < 16; i++) s[i] = S[s[i]];
      var t = s.slice(0);
      for (c = 0; c < 4; c++) for (i = 0; i < 4; i++) s[c * 4 + i] = t[((c + i) % 4) * 4 + i];
      if (r < 10) {
        for (c = 0; c < 4; c++) {
          var a0 = s[c * 4], a1 = s[c * 4 + 1], a2 = s[c * 4 + 2], a3 = s[c * 4 + 3], all = a0 ^ a1 ^ a2 ^ a3;
          s[c * 4] ^= all ^ xt(a0 ^ a1); s[c * 4 + 1] ^= all ^ xt(a1 ^ a2);
          s[c * 4 + 2] ^= all ^ xt(a2 ^ a3); s[c * 4 + 3] ^= all ^ xt(a3 ^ a0);
        }
      }
      for (i = 0; i < 16; i++) s[i] ^= w[r * 16 + i];
    }
    return s;
  }

  // GF(2^128) multiply for GHASH, bit by bit (inputs are 16-byte arrays).
  function gmul(x, y) {
    var z = new Array(16).fill(0), v = y.slice(0);
    for (var i = 0; i < 128; i++) {
      if ((x[i >> 3] >> (7 - (i & 7))) & 1) for (var k = 0; k < 16; k++) z[k] ^= v[k];
      var lsb = v[15] & 1;
      for (k = 15; k > 0; k--) v[k] = ((v[k] >> 1) | ((v[k - 1] & 1) << 7)) & 0xff;
      v[0] >>= 1;
      if (lsb) v[0] ^= 0xe1;
    }
    return z;
  }

  function ghash(h, data) {
    var y = new Array(16).fill(0);
    for (var i = 0; i < data.length; i += 16) {
      for (var k = 0; k < 16; k++) y[k] ^= (data[i + k] || 0);
      y = gmul(y, h);
    }
    return y;
  }

  function inc(ctr) { for (var i = 15; i >= 12; i--) { ctr[i] = (ctr[i] + 1) & 0xff; if (ctr[i]) break; } }

  function encrypt(key, iv, pt) {
    var w = expand(key), h = encBlock(w, new Array(16).fill(0));
    var j0 = iv.concat([0, 0, 0, 1]), ctr = j0.slice(0), ct = [];
    for (var i = 0; i < pt.length; i += 16) {
      inc(ctr);
      var ks = encBlock(w, ctr);
      for (var k = 0; k < 16 && i + k < pt.length; k++) ct.push(pt[i + k] ^ ks[k]);
    }
    var padded = ct.slice(0); while (padded.length % 16) padded.push(0);
    var bits = ct.length * 8, len = new Array(16).fill(0);
    for (i = 0; i < 6; i++) len[15 - i] = Math.floor(bits / Math.pow(256, i)) & 0xff;
    var s = ghash(h, padded.concat(len)), ek = encBlock(w, j0), tag = [];
    for (i = 0; i < 16; i++) tag.push(s[i] ^ ek[i]);
    return ct.concat(tag);
  }

  function b64urlDecode(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '=';
    var bin = atob(s), out = [];
    for (var i = 0; i < bin.length; i++) out.push(bin.charCodeAt(i));
    return out;
  }
  function b64urlEncode(bytes) {
    var bin = ''; for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function utf8(str) { return Array.prototype.slice.call(new TextEncoder().encode(str)); }

  function randomIv() {
    var iv = new Uint8Array(12);
    (root.crypto || root.msCrypto).getRandomValues(iv);
    return Array.prototype.slice.call(iv);
  }

  /** Encrypts a string with a base64url 16-byte key; returns base64url(iv||ct||tag). */
  root.dekSeal = function (keyB64, text, ivOverride) {
    var iv = ivOverride || randomIv();
    return b64urlEncode(iv.concat(encrypt(b64urlDecode(keyB64), iv, utf8(text))));
  };
})(typeof window !== 'undefined' ? window : globalThis);

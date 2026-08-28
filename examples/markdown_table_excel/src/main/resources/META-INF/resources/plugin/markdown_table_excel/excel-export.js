/*
 * markdown_table_excel — client-side export of rendered markdown tables to .xlsx
 *
 * This script is shipped by the "markdown_table_excel" extension. It watches the
 * chat view for rendered markdown tables (produced by marked.js) and attaches a
 * "导出Excel" button above each table. Clicking the button builds a real .xlsx
 * file entirely in the browser (minimal ZIP/STORE writer + OOXML) and downloads it.
 * No backend round-trip is involved.
 */
(function () {
  'use strict';

  function ensureExcelStyles() {
    if (document.getElementById('excel-export-style')) return;
    var css = [
      '.excel-table-wrap { position: relative; margin: 8px 0; }',
      '.excel-table-bar { display: flex; justify-content: flex-end; margin-bottom: 6px; }',
      '.excel-export-btn { cursor: pointer; border: 1px solid #d9d9d9; background: #fff;',
      '  color: #2b6cb0; border-radius: 4px; padding: 4px 10px; font-size: 12px; }',
      '.excel-export-btn:hover { background: #f0f7ff; border-color: #2b6cb0; }'
    ].join(' ');
    var style = document.createElement('style');
    style.id = 'excel-export-style';
    style.textContent = css;
    document.head.appendChild(style);
  }

  function escapeXml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;'
        : c === '"' ? '&quot;' : '&apos;';
    });
  }

  function colLetter(c) {
    var s = '';
    c = c + 1;
    while (c > 0) {
      var m = (c - 1) % 26;
      s = String.fromCharCode(65 + m) + s;
      c = Math.floor((c - 1) / 26);
    }
    return s;
  }

  function crc32(u8) {
    if (!crc32.table) {
      var t = [];
      for (var n = 0; n < 256; n++) {
        var c = n;
        for (var k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c >>> 0;
      }
      crc32.table = t;
    }
    var crc = 0xFFFFFFFF;
    for (var i = 0; i < u8.length; i++) crc = (crc >>> 8) ^ crc32.table[(crc ^ u8[i]) & 0xFF];
    return (crc ^ 0xFFFFFFFF) >>> 0;
  }

  function strToU8(s) {
    return new TextEncoder().encode(s);
  }

  function u16(n) { return new Uint8Array([n & 0xFF, (n >>> 8) & 0xFF]); }
  function u32(n) {
    return new Uint8Array([n & 0xFF, (n >>> 8) & 0xFF, (n >>> 16) & 0xFF, (n >>> 24) & 0xFF]);
  }

  function zipStore(files) {
    var chunks = [];
    var central = [];
    var offset = 0;
    for (var i = 0; i < files.length; i++) {
      var nameBytes = strToU8(files[i].name);
      var data = files[i].data;
      var crc = crc32(data);
      var lh = new Uint8Array(30 + nameBytes.length);
      var dv = new DataView(lh.buffer);
      dv.setUint32(0, 0x04034b50, true);
      dv.setUint16(4, 20, true);
      dv.setUint16(6, 0, true);
      dv.setUint16(8, 0, true);
      dv.setUint16(10, 0, true);
      dv.setUint16(12, 0, true);
      dv.setUint32(14, crc, true);
      dv.setUint32(18, data.length, true);
      dv.setUint32(22, data.length, true);
      dv.setUint16(26, nameBytes.length, true);
      dv.setUint16(28, 0, true);
      lh.set(nameBytes, 30);
      chunks.push(lh);
      chunks.push(data);

      var cd = new Uint8Array(46 + nameBytes.length);
      var cdv = new DataView(cd.buffer);
      cdv.setUint32(0, 0x02014b50, true);
      cdv.setUint16(4, 20, true);
      cdv.setUint16(6, 20, true);
      cdv.setUint16(8, 0, true);
      cdv.setUint16(10, 0, true);
      cdv.setUint16(12, 0, true);
      cdv.setUint32(16, crc, true);
      cdv.setUint32(20, data.length, true);
      cdv.setUint32(24, data.length, true);
      cdv.setUint16(28, nameBytes.length, true);
      cdv.setUint16(30, 0, true);
      cdv.setUint16(32, 0, true);
      cdv.setUint16(34, 0, true);
      cdv.setUint16(36, 0, true);
      cdv.setUint32(38, 0, true);
      cdv.setUint32(42, offset, true);
      cd.set(nameBytes, 46);
      central.push(cd);
      offset += lh.length + data.length;
    }
    var centralSize = 0;
    var centralStart = offset;
    for (var j = 0; j < central.length; j++) {
      chunks.push(central[j]);
      centralSize += central[j].length;
    }
    var eocd = new Uint8Array(22);
    var edv = new DataView(eocd.buffer);
    edv.setUint32(0, 0x06054b50, true);
    edv.setUint16(4, 0, true);
    edv.setUint16(6, 0, true);
    edv.setUint16(8, files.length, true);
    edv.setUint16(10, files.length, true);
    edv.setUint32(12, centralSize, true);
    edv.setUint32(16, centralStart, true);
    edv.setUint16(20, 0, true);
    chunks.push(eocd);

    var total = 0;
    for (var k = 0; k < chunks.length; k++) total += chunks[k].length;
    var out = new Uint8Array(total);
    var pos = 0;
    for (var m = 0; m < chunks.length; m++) { out.set(chunks[m], pos); pos += chunks[m].length; }
    return out;
  }

  function buildXlsx(header, rows, sheetName) {
    sheetName = sheetName || 'Sheet1';
    var sheet = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n';
    sheet += '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">';
    sheet += '<sheetData>';
    var all = [header].concat(rows);
    for (var r = 0; r < all.length; r++) {
      sheet += '<row r="' + (r + 1) + '">';
      var cells = all[r];
      for (var c = 0; c < cells.length; c++) {
        var ref = colLetter(c) + (r + 1);
        var val = (cells[c] == null) ? '' : String(cells[c]);
        var num = val !== '' && !isNaN(Number(val)) && isFinite(Number(val));
        if (num) {
          sheet += '<c r="' + ref + '"><v>' + escapeXml(val) + '</v></c>';
        } else {
          sheet += '<c r="' + ref + '" t="inlineStr"><is><t xml:space="preserve">' +
            escapeXml(val) + '</t></is></c>';
        }
      }
      sheet += '</row>';
    }
    sheet += '</sheetData></worksheet>';

    var contentTypes =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n' +
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
      '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
      '</Types>';

    var rels =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
      '</Relationships>';

    var workbook =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n' +
      '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
      '<sheets><sheet name="' + escapeXml(sheetName) + '" sheetId="1" r:id="rId1"/></sheets>' +
      '</workbook>';

    var wbRels =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
      '</Relationships>';

    return zipStore([
      { name: '[Content_Types].xml', data: strToU8(contentTypes) },
      { name: '_rels/.rels', data: strToU8(rels) },
      { name: 'xl/workbook.xml', data: strToU8(workbook) },
      { name: 'xl/_rels/workbook.xml.rels', data: strToU8(wbRels) },
      { name: 'xl/worksheets/sheet1.xml', data: strToU8(sheet) }
    ]);
  }

  function cellText(el) {
    return el.textContent || '';
  }

  // 还原被“逐字符拆成单元格”的脏 DOM：
  //  - 若行内存在单独的 "|" 单元格（来自 "a|b" 形式的字符串列），以 "|" 为列分隔符重新拼回多列；
  //  - 若整行都是单字符单元格（纯逐字符拆分、无分隔符），则合并为一列（保留完整文本，不再一格一字）。
  function reconstructRow(raw) {
    var hasSep = false;
    for (var i = 0; i < raw.length; i++) {
      if (raw[i] === '|') { hasSep = true; break; }
    }
    if (hasSep) {
      var cols = [], cur = [];
      for (var j = 0; j < raw.length; j++) {
        if (raw[j] === '|') { cols.push(cur.join('').trim()); cur = []; }
        else cur.push(raw[j]);
      }
      cols.push(cur.join('').trim());
      return cols;
    }
    var allSingle = raw.length > 1;
    for (var k = 0; k < raw.length; k++) {
      if ((raw[k] || '').length !== 1) { allSingle = false; break; }
    }
    if (allSingle) return [raw.join('').trim()];
    return raw.map(function (s) { return (s || '').trim(); });
  }

  function readTable(table) {
    var headerRaw = [];
    var headerCells = table.querySelectorAll('thead th');
    if (headerCells.length) {
      for (var i = 0; i < headerCells.length; i++) headerRaw.push(cellText(headerCells[i]));
    }
    var bodyRows = table.querySelectorAll('tbody tr');
    if (!bodyRows.length) {
      bodyRows = table.querySelectorAll('tr');
      if (headerCells.length && bodyRows.length) bodyRows = Array.prototype.slice.call(bodyRows, 1);
    }
    var rows = [];
    for (var r = 0; r < bodyRows.length; r++) {
      var cells = bodyRows[r].querySelectorAll('td,th');
      var raw = [];
      for (var c = 0; c < cells.length; c++) raw.push(cellText(cells[c]));
      rows.push(reconstructRow(raw));
    }
    var header = reconstructRow(headerRaw);
    // 规整为矩形：列数取最大值，缺失单元格补空
    var maxCols = header.length;
    for (var m = 0; m < rows.length; m++) if (rows[m].length > maxCols) maxCols = rows[m].length;
    while (header.length < maxCols) header.push('');
    for (var p = 0; p < rows.length; p++) {
      while (rows[p].length < maxCols) rows[p].push('');
    }
    return { header: header, rows: rows };
  }

  function exportTableToExcel(table, filename) {
    var data = readTable(table);
    if (!data.header.length && !data.rows.length) return;
    var blob = new Blob([buildXlsx(data.header, data.rows, filename || 'table')], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = (filename || 'table') + '.xlsx';
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(url); a.remove(); }, 1000);
  }

  function enhanceTable(table) {
    if (table.getAttribute('data-excel-enhanced')) return;
    table.setAttribute('data-excel-enhanced', '1');
    var wrap = document.createElement('div');
    wrap.className = 'excel-table-wrap';
    table.parentNode.insertBefore(wrap, table);
    var bar = document.createElement('div');
    bar.className = 'excel-table-bar';
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'excel-export-btn';
    btn.textContent = '导出Excel';
    btn.addEventListener('click', function () { exportTableToExcel(table, 'table-export'); });
    bar.appendChild(btn);
    wrap.appendChild(bar);
    wrap.appendChild(table);
  }

  function enhanceWithin(root) {
    if (!root) return;
    var tables = root.querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) enhanceTable(tables[i]);
  }

  function init() {
    ensureExcelStyles();
    var chatView = document.getElementById('chatView');
    if (!chatView) return;
    enhanceWithin(chatView);
    if (window.MutationObserver) {
      var obs = new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var added = muts[i].addedNodes;
          for (var j = 0; j < added.length; j++) {
            var node = added[j];
            if (node.nodeType !== 1) continue;
            if (node.tagName === 'TABLE') enhanceTable(node);
            else if (node.querySelector) enhanceWithin(node);
          }
        }
      });
      obs.observe(chatView, { childList: true, subtree: true });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

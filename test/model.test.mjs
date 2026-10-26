// Browser integration is tested separately; these tests exercise the compiled
// Squint model itself rather than a duplicate JavaScript implementation.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  csv_rows, parse_date, import_csv, import_json, export_csv,
  record_date_time, valid_values_QMARK_, fingerprint
} from '../js/fuel_log/model.mjs';

const id = () => 'test-id';
test('dates are strict ISO year-month-day or legacy US format', () => {
  assert.equal(parse_date('2026-10-24'), '2026-10-24');
  assert.equal(parse_date('10/24/2026'), '2026-10-24');
  assert.equal(parse_date('2026-02-30'), null);
  assert.equal(parse_date('02/30/2026'), null);
});

test('CSV parser handles quoted commas, newlines and quotes', () => {
  assert.deepEqual(csv_rows('A,B\r\n"Hi, there","a""b"\r\n'), [['A','B'],['Hi, there','a"b']]);
  assert.throws(() => csv_rows('a,"unfinished'), /unclosed quote/);
});

test('imports historical Google Sheet columns without inventing timestamps', () => {
  const s = 'Timestamp,Date,Current Mileage,Trip Miles,Gallons,Cost\r\n' +
            ',2025-01-01,"100,000",250.1,10.123,$34.50\r\n' +
            '10/24/2026 16:18:36,10/24/2026,108604,284.8,13.141,46.37\r\n';
  const out = import_csv(s, [], id);
  assert.equal(out.added, 2);
  assert.equal(out.records[0].recordedAt, null);
  assert.equal(out.records[0].date, '2025-01-01');
  assert.equal(record_date_time(out.records[0]), '2025-01-01');
  assert.equal(record_date_time(out.records[1]), '2026-10-24 16:18');
  const again = import_csv(s, out.records, id);
  assert.equal(again.added, 0);
  assert.equal(again.duplicates, 2);
});

test('exports can be reimported without losing legacy origin fields', () => {
  const sample = [{id:'r1', date:'2026-03-01', mileage:1020, trip:220, gallons:8.1, cost:23.45,
                   recordedAt:'2026-03-01T12:24:00.000Z', sourceTimestamp:'', origin:'app'}];
  const exported = export_csv(sample);
  assert.match(exported, /Recorded At,Date,Current Mileage/);
  const { records } = import_csv(exported, [], id);
  assert.equal(records.length, 1);
  assert.equal(fingerprint(records[0]), fingerprint(sample[0]));
  assert.equal(records[0].recordedAt, sample[0].recordedAt);
});

test('JSON backups retain the previous fuel-mileage-v1 schema', () => {
  const obj = {format:'fuel-mileage-v1', entries:[{date:'2026-03-01', mileage:500, trip:12,
    gallons:1.4, cost:3.5, origin:'app', sourceTimestamp:''}]};
  assert.equal(import_json(JSON.stringify(obj), [], id).added, 1);
  assert.throws(() => import_json('{}', [], id), /Unrecognized JSON/);
});

test('all required quantities must be finite and legal', () => {
  assert.equal(valid_values_QMARK_(100000, 250.5, 11.2, 40), true);
  assert.equal(valid_values_QMARK_(100000, 250.5, 0, 40), false);
  assert.equal(valid_values_QMARK_(NaN, 250.5, 11.2, 40), false);
});

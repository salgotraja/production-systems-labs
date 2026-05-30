/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.engnotes.labs.commons.report;

import dev.engnotes.labs.commons.csv.CsvTableReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReportIndexWriter {

    private ReportIndexWriter() {}

    public record Series(String name, Path csvPath) {}

    public static void write(
            int postNumber,
            String title,
            Path reportPath,
            Path manifestPath,
            List<Series> series) throws IOException {

        Files.createDirectories(reportPath.getParent() != null ? reportPath.getParent() : Path.of("."));
        Files.writeString(
                reportPath,
                html(postNumber, title, manifestPath, series),
                StandardCharsets.UTF_8);
    }

    static String html(
            int postNumber,
            String title,
            Path manifestPath,
            List<Series> series) throws IOException {

        String data = dataJson(manifestPath, series).replace("</", "<\\/");
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>__HTML_TITLE__</title>
                <style>
                body{margin:0;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#151515;background:#f7f7f4}
                header{padding:28px 32px 18px;background:#20241f;color:#fff}
                h1{margin:0;font-size:28px;font-weight:650;line-height:1.2}
                main{display:grid;grid-template-columns:minmax(0,1fr) 360px;gap:24px;padding:24px 32px}
                .panel{background:#fff;border:1px solid #d9d9d2;border-radius:6px;padding:18px}
                .controls{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px}
                fieldset{border:1px solid #d9d9d2;border-radius:4px;margin:0;padding:8px;display:flex;gap:6px;align-items:center}
                legend{font-size:12px;color:#4d514a;padding:0 4px}
                button{font:inherit;border:1px solid #aeb2aa;background:#fff;color:#20241f;border-radius:4px;padding:7px 9px;cursor:pointer}
                button.active{background:#216e5d;color:#fff;border-color:#216e5d}
                button:disabled{cursor:not-allowed;color:#878b83;background:#efefea;border-color:#d4d6cf}
                label{display:grid;gap:4px;font-size:12px;color:#4d514a}
                select{font:inherit;padding:8px 10px;border:1px solid #babdb5;border-radius:4px;background:#fff}
                svg{width:100%;height:380px;border:1px solid #e2e2dc;background:#fbfbf8}
                table{width:100%;border-collapse:collapse;font-size:12px}
                th,td{padding:6px 8px;border-bottom:1px solid #ecece6;text-align:right;white-space:nowrap}
                th:first-child,td:first-child{text-align:left}
                pre{white-space:pre-wrap;word-break:break-word;font-size:12px;line-height:1.45;background:#f2f2ed;padding:12px;border-radius:4px}
                @media (max-width:900px){main{display:block;padding:18px}.panel{margin-bottom:18px}header{padding:22px 18px}}
                </style>
                </head>
                <body>
                <header><h1>Post __POST_NUMBER__: __HTML_TITLE__</h1></header>
                <main>
                  <section class="panel">
                    <div class="controls">
                      <label>Series<select id="series"></select></label>
                      <label>Metric<select id="metric"></select></label>
                      <fieldset><legend>Measurement</legend><span id="measurement-controls"></span></fieldset>
                      <fieldset><legend>Hedging</legend><span id="hedging-controls"></span></fieldset>
                      <fieldset><legend>View</legend><span id="view-controls"></span></fieldset>
                    </div>
                    <svg id="chart" role="img" aria-label="CSV chart"></svg>
                    <div id="table"></div>
                  </section>
                  <aside class="panel">
                    <h2>Manifest</h2>
                    <pre id="manifest"></pre>
                  </aside>
                </main>
                <script type="application/json" id="report-data">__DATA__</script>
                <script>
                const data = JSON.parse(document.getElementById('report-data').textContent);
                const seriesSelect = document.getElementById('series');
                const metricSelect = document.getElementById('metric');
                const chart = document.getElementById('chart');
                const table = document.getElementById('table');
                const measurementControls = document.getElementById('measurement-controls');
                const hedgingControls = document.getElementById('hedging-controls');
                const viewControls = document.getElementById('view-controls');
                document.getElementById('manifest').textContent = JSON.stringify(data.manifest, null, 2);
                data.series.forEach((s, i) => seriesSelect.add(new Option(s.name + ' (' + s.file + ')', i)));
                function numericColumns(rows) {
                  if (!rows.length) return [];
                  return Object.keys(rows[0]).filter(k => rows.some(r => typeof r[k] === 'number'));
                }
                function chooseMetric(columns) {
                  const preferred = ['p99_ms', 'p999_ms', 'p95_ms', 'p50_ms', 'throughput_rps', 'burn_rate'];
                  return preferred.find(c => columns.includes(c)) || columns[0];
                }
                function seriesIndexMatching(pattern) {
                  return data.series.findIndex(s => pattern.test(s.name.toLowerCase()));
                }
                function metricMatching(columns, names) {
                  return names.find(name => columns.includes(name));
                }
                function setSeries(pattern) {
                  const index = seriesIndexMatching(pattern);
                  if (index >= 0) {
                    seriesSelect.value = String(index);
                    refreshMetrics();
                  }
                }
                function setMetric(names) {
                  const columns = numericColumns(data.series[seriesSelect.value].rows);
                  const metric = metricMatching(columns, names);
                  if (metric) {
                    metricSelect.value = metric;
                    render();
                  }
                }
                function addButton(container, label, disabled, active, title, onClick) {
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.textContent = label;
                  button.disabled = disabled;
                  button.title = title;
                  if (active) button.classList.add('active');
                  button.addEventListener('click', onClick);
                  container.appendChild(button);
                }
                function refreshTruthControls() {
                  const activeSeries = data.series[seriesSelect.value].name.toLowerCase();
                  const columns = numericColumns(data.series[seriesSelect.value].rows);
                  measurementControls.replaceChildren();
                  hedgingControls.replaceChildren();
                  viewControls.replaceChildren();

                  const hasRaw = seriesIndexMatching(/closed-loop raw/) >= 0;
                  const hasCorrected = seriesIndexMatching(/corrected/) >= 0;
                  const hasOpenLoop = seriesIndexMatching(/open-loop/) >= 0;
                  addButton(measurementControls, 'Closed raw', !hasRaw, activeSeries.includes('closed-loop raw'),
                    hasRaw ? 'Closed-loop measurement without coordinated-omission correction' : 'Active in Post 4',
                    () => setSeries(/closed-loop raw/));
                  addButton(measurementControls, 'CO-corrected', !hasCorrected, activeSeries.includes('corrected'),
                    hasCorrected ? 'HdrHistogram coordinated-omission corrected series' : 'Active in Post 4',
                    () => setSeries(/corrected/));
                  addButton(measurementControls, 'Open-loop', !hasOpenLoop, activeSeries.includes('open-loop'),
                    hasOpenLoop ? 'Open-loop measurement series' : 'Active in Post 4',
                    () => setSeries(/open-loop/));

                  const hasBaseline = seriesIndexMatching(/baseline/) >= 0;
                  const hasHedged = seriesIndexMatching(/hedged/) >= 0;
                  addButton(hedgingControls, 'Off', !hasHedged || !hasBaseline, hasHedged && activeSeries.includes('baseline'),
                    hasHedged && hasBaseline ? 'Baseline series without hedging' : 'Active in Post 3',
                    () => setSeries(/baseline/));
                  addButton(hedgingControls, 'On', !hasHedged, hasHedged && activeSeries.includes('hedged'),
                    hasHedged ? 'Selected hedged-request series' : 'Active in Post 3',
                    () => setSeries(/hedged/));

                  const avgMetric = metricMatching(columns, ['avg_ms', 'mean_ms', 'mean_sojourn_ms']);
                  const p99Metric = metricMatching(columns, ['p99_ms', 'whole_run_p99_ms']);
                  const maxMetric = metricMatching(columns, ['max_ms', 'max_buffered', 'p999_ms']);
                  addButton(viewControls, 'Avg', !avgMetric, metricSelect.value === avgMetric,
                    avgMetric ? avgMetric : 'No average column in this CSV',
                    () => setMetric(['avg_ms', 'mean_ms', 'mean_sojourn_ms']));
                  addButton(viewControls, 'p99', !p99Metric, metricSelect.value === p99Metric,
                    p99Metric ? p99Metric : 'No p99 column in this CSV',
                    () => setMetric(['p99_ms', 'whole_run_p99_ms']));
                  addButton(viewControls, 'Max', !maxMetric, metricSelect.value === maxMetric,
                    maxMetric ? maxMetric : 'No max column in this CSV',
                    () => setMetric(['max_ms', 'max_buffered', 'p999_ms']));
                }
                function refreshMetrics() {
                  const rows = data.series[seriesSelect.value].rows;
                  const columns = numericColumns(rows);
                  const previous = metricSelect.value;
                  metricSelect.replaceChildren();
                  columns.forEach(c => metricSelect.add(new Option(c, c)));
                  metricSelect.value = columns.includes(previous) ? previous : chooseMetric(columns);
                  render();
                }
                function render() {
                  refreshTruthControls();
                  const selected = data.series[seriesSelect.value];
                  const rows = selected.rows;
                  const metric = metricSelect.value;
                  renderChart(rows, metric);
                  renderTable(rows);
                }
                function renderChart(rows, metric) {
                  chart.replaceChildren();
                  const values = rows.map((r, i) => ({x: Number(r.elapsed_s ?? r.elapsed_seconds ?? i + 1), y: Number(r[metric])}))
                    .filter(p => Number.isFinite(p.x) && Number.isFinite(p.y));
                  if (!values.length) return;
                  const w = 900, h = 380, p = 42;
                  chart.setAttribute('viewBox', `0 0 ${w} ${h}`);
                  const xs = values.map(v => v.x), ys = values.map(v => v.y);
                  const minX = Math.min(...xs), maxX = Math.max(...xs);
                  const minY = Math.min(0, ...ys), maxY = Math.max(...ys);
                  const sx = x => p + ((x - minX) / Math.max(1, maxX - minX)) * (w - p * 2);
                  const sy = y => h - p - ((y - minY) / Math.max(1, maxY - minY)) * (h - p * 2);
                  const axis = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                  axis.setAttribute('d', `M${p},${p} V${h-p} H${w-p}`);
                  axis.setAttribute('fill', 'none');
                  axis.setAttribute('stroke', '#8d9188');
                  chart.appendChild(axis);
                  const line = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
                  line.setAttribute('points', values.map(v => `${sx(v.x)},${sy(v.y)}`).join(' '));
                  line.setAttribute('fill', 'none');
                  line.setAttribute('stroke', '#216e5d');
                  line.setAttribute('stroke-width', '3');
                  chart.appendChild(line);
                  [['0', p, h - 14], [metric + ' max ' + maxY.toFixed(1), p, 20], ['elapsed_s', w - 100, h - 14]]
                    .forEach(([text, x, y]) => {
                      const t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                      t.textContent = text;
                      t.setAttribute('x', x);
                      t.setAttribute('y', y);
                      t.setAttribute('font-size', '13');
                      t.setAttribute('fill', '#333');
                      chart.appendChild(t);
                    });
                }
                function renderTable(rows) {
                  const shown = rows.slice(0, 30);
                  table.replaceChildren();
                  if (!shown.length) return;
                  const columns = Object.keys(shown[0]);
                  const tableEl = document.createElement('table');
                  const thead = document.createElement('thead');
                  const headRow = document.createElement('tr');
                  columns.forEach(c => {
                    const th = document.createElement('th');
                    th.textContent = c;
                    headRow.appendChild(th);
                  });
                  thead.appendChild(headRow);
                  tableEl.appendChild(thead);
                  const tbody = document.createElement('tbody');
                  shown.forEach(r => {
                    const tr = document.createElement('tr');
                    columns.forEach(c => {
                      const td = document.createElement('td');
                      td.textContent = r[c];
                      tr.appendChild(td);
                    });
                    tbody.appendChild(tr);
                  });
                  tableEl.appendChild(tbody);
                  table.appendChild(tableEl);
                }
                seriesSelect.addEventListener('change', refreshMetrics);
                metricSelect.addEventListener('change', render);
                refreshMetrics();
                </script>
                </body>
                </html>
                """
                .replace("__HTML_TITLE__", escapeHtml(title))
                .replace("__POST_NUMBER__", Integer.toString(postNumber))
                .replace("__DATA__", data);
    }

    private static String dataJson(Path manifestPath, List<Series> series) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"manifest\":");
        json.append(Files.readString(manifestPath, StandardCharsets.UTF_8));
        json.append(",\"series\":[");
        for (int i = 0; i < series.size(); i++) {
            Series item = series.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"name\":").append(quote(item.name()));
            json.append(",\"file\":").append(quote(item.csvPath().getFileName().toString()));
            json.append(",\"rows\":").append(csvRowsJson(item.csvPath())).append("}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String csvRowsJson(Path csvPath) throws IOException {
        CsvTableReader.Table table = CsvTableReader.read(csvPath);
        if (table.headers().isEmpty()) {
            return "[]";
        }

        StringBuilder json = new StringBuilder("[");
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            if (rowIndex > 0) {
                json.append(",");
            }
            json.append("{");
            List<String> row = table.rows().get(rowIndex);
            for (int col = 0; col < table.headers().size(); col++) {
                if (col > 0) {
                    json.append(",");
                }
                String value = col < row.size() ? row.get(col) : "";
                json.append(quote(table.headers().get(col))).append(":").append(jsonValue(value));
            }
            json.append("}");
        }
        json.append("]");
        return json.toString();
    }

    private static String jsonValue(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return value.toLowerCase();
        }
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException ignored) {
            return quote(value);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

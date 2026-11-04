function formatTimestamp() {
  const now = new Date();
  const pad = (n, z = 2) => String(n).padStart(z, "0");
  const hh = pad(now.getHours());
  const mm = pad(now.getMinutes());
  const ss = pad(now.getSeconds());
  const ms = pad(now.getMilliseconds(), 3);
  return `${hh}:${mm}:${ss}.${ms}`;
}

function escapeHtml(str) {
  if (typeof str !== "string") {
    try {
      str = JSON.stringify(str, null, 2);
    } catch {
      str = String(str);
    }
  }
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function showNotification(message, type = "info") {
  console.log(`[Alert] ${type.toUpperCase()}: ${message}`);

  const container = document.getElementById("toastContainer");
  if (!container) {
    alert(message);
    return;
  }

  const toast = document.createElement("div");
  toast.className = `toast-enter flex items-center justify-between gap-3 px-4 py-3 rounded-lg shadow-lg border text-sm max-w-sm ${
    type === "warning"
      ? "bg-amber-50 text-amber-900 border-amber-300"
      : type === "error"
      ? "bg-red-50 text-red-900 border-red-300"
      : "bg-white text-gray-900 border-gray-200 shadow-md"
  }`;

  toast.innerHTML = `
    <span class="font-medium">${escapeHtml(message)}</span>
    <button class="text-gray-400 hover:text-gray-700 font-bold ml-2 select-none">&times;</button>
  `;

  const closeBtn = toast.querySelector("button");
  const dismiss = () => {
    toast.classList.remove("toast-enter");
    toast.classList.add("toast-exit");
    setTimeout(() => toast.remove(), 150);
  };

  closeBtn.addEventListener("click", dismiss);
  container.appendChild(toast);
  setTimeout(dismiss, 3500);
}

function addActivityLog(type, message) {
  const logContainer = document.getElementById("activityLog");
  if (!logContainer) return;

  const timestamp = formatTimestamp();
  const line = document.createElement("div");
  line.className = "flex items-start gap-2.5 py-1 text-xs font-mono border-b border-gray-800 last:border-b-0";

  let badgeColor = "bg-sky-500/20 text-sky-300 border-sky-500/40";
  if (type === "PUT") badgeColor = "bg-emerald-500/20 text-emerald-300 border-emerald-500/40";
  else if (type === "GET") badgeColor = "bg-blue-500/20 text-blue-300 border-blue-500/40";
  else if (type === "DELETE") badgeColor = "bg-rose-500/20 text-rose-300 border-rose-500/40";
  else if (type === "BENCHMARK") badgeColor = "bg-purple-500/20 text-purple-300 border-purple-500/40";
  else if (type === "INFO") badgeColor = "bg-amber-500/20 text-amber-300 border-amber-500/40";
  else if (type === "ERROR") badgeColor = "bg-red-500/30 text-red-300 border-red-500/50 font-bold";

  line.innerHTML = `
    <span class="text-gray-500 select-none">${timestamp}</span>
    <span class="px-1.5 py-0.2 rounded border text-[10px] font-semibold tracking-wider ${badgeColor}">${type}</span>
    <span class="text-gray-300 break-all">${message}</span>
  `;

  logContainer.appendChild(line);
  logContainer.scrollTop = logContainer.scrollHeight;
}

function clearLog() {
  const logContainer = document.getElementById("activityLog");
  if (logContainer) logContainer.innerHTML = "";
  addActivityLog("INFO", "Activity log cleared");
}

function updateResultBox({ status, foundIn, latencyMs, value, error }) {
  const resultBox = document.getElementById("resultBox");
  if (!resultBox) return;

  const statusStr = String(status || (error ? "ERROR" : "200 OK"));
  let badgeStyle = "bg-emerald-100 text-emerald-800 border-emerald-300";

  if (statusStr.includes("400") || statusStr.includes("404")) {
    badgeStyle = "bg-amber-100 text-amber-800 border-amber-300";
  } else if (statusStr.includes("500") || error) {
    badgeStyle = "bg-red-100 text-red-800 border-red-300";
  } else if (statusStr.includes("204")) {
    badgeStyle = "bg-sky-100 text-sky-800 border-sky-300";
  }

  let html = `
    <div class="space-y-3 w-full">
      <div class="flex flex-wrap items-center gap-2">
        <span class="px-2.5 py-0.5 rounded text-xs font-semibold border ${badgeStyle}">${escapeHtml(statusStr)}</span>
        ${foundIn ? `<span class="text-xs text-gray-500 font-medium">&bull; ${escapeHtml(foundIn)}</span>` : ""}
        ${latencyMs !== undefined ? `<span class="text-xs text-gray-500">&bull; Latency: <span class="font-mono text-gray-700 font-semibold">${latencyMs.toFixed(2)} ms</span></span>` : ""}
      </div>
  `;

  if (value !== undefined && value !== null && value !== "") {
    html += `
      <div class="rounded-md bg-gray-900 p-3 text-xs text-emerald-400 font-mono select-text whitespace-pre-wrap overflow-x-auto">
        ${escapeHtml(value)}
      </div>
    `;
  }

  if (error) {
    html += `
      <div class="rounded-md bg-red-50 border border-red-200 p-3 text-xs text-red-700 font-mono">
        ${escapeHtml(error)}
      </div>
    `;
  }

  html += `</div>`;
  resultBox.innerHTML = html;
}

async function handlePut() {
  const keyInput = document.getElementById("keyInput");
  const valueInput = document.getElementById("valueInput");
  const key = keyInput.value.trim();
  const value = valueInput.value;

  if (!key) {
    updateResultBox({
      status: "400 Bad Request",
      foundIn: "Validation Error",
      error: "Key cannot be empty"
    });
    addActivityLog("ERROR", "PUT failed: Key cannot be empty");
    showNotification("Key cannot be empty", "warning");
    return;
  }

  const startTime = performance.now();

  try {
    const response = await fetch("/api/entries", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key, value })
    });
    const latency = performance.now() - startTime;

    if (response.ok) {
      updateResultBox({
        status: "201 Created",
        foundIn: "Storage Engine",
        latencyMs: latency,
        value: value
      });
      addActivityLog("PUT", `key=${key} -> Persisted to storage engine`);
    } else {
      const errText = await response.text();
      updateResultBox({
        status: `${response.status} ${response.statusText}`,
        latencyMs: latency,
        error: errText || "Failed to create entry"
      });
      addActivityLog("ERROR", `PUT key=${key} failed with status ${response.status}`);
    }
  } catch (err) {
    const latency = performance.now() - startTime;
    updateResultBox({
      status: "Network Error",
      latencyMs: latency,
      error: err.message
    });
    addActivityLog("ERROR", `PUT key=${key} network error: ${err.message}`);
  }
}

async function handleGet() {
  const keyInput = document.getElementById("keyInput");
  const key = keyInput.value.trim();

  if (!key) {
    updateResultBox({
      status: "400 Bad Request",
      foundIn: "Validation Error",
      error: "Key cannot be empty"
    });
    addActivityLog("ERROR", "GET failed: Key cannot be empty");
    showNotification("Key cannot be empty", "warning");
    return;
  }

  const startTime = performance.now();

  try {
    const response = await fetch(`/api/entries/${encodeURIComponent(key)}`);
    const latency = performance.now() - startTime;

    if (response.ok) {
      const text = await response.text();
      updateResultBox({
        status: "200 OK",
        foundIn: "Storage Engine",
        latencyMs: latency,
        value: text
      });
      addActivityLog("GET", `key=${key} -> Found in storage engine [${latency.toFixed(2)} ms]`);
    } else if (response.status === 404) {
      updateResultBox({
        status: "404 Not Found",
        foundIn: "Not Found",
        latencyMs: latency,
        value: null
      });
      addActivityLog("GET", `key=${key} -> Entry not found (404)`);
    } else {
      const errText = await response.text();
      updateResultBox({
        status: `${response.status} ${response.statusText}`,
        latencyMs: latency,
        error: errText
      });
      addActivityLog("ERROR", `GET key=${key} returned status ${response.status}`);
    }
  } catch (err) {
    const latency = performance.now() - startTime;
    updateResultBox({
      status: "Network Error",
      latencyMs: latency,
      error: err.message
    });
    addActivityLog("ERROR", `GET key=${key} error: ${err.message}`);
  }
}

async function handleDelete() {
  const keyInput = document.getElementById("keyInput");
  const key = keyInput.value.trim();

  if (!key) {
    updateResultBox({
      status: "400 Bad Request",
      foundIn: "Validation Error",
      error: "Key cannot be empty"
    });
    addActivityLog("ERROR", "DELETE failed: Key cannot be empty");
    showNotification("Key cannot be empty", "warning");
    return;
  }

  const startTime = performance.now();

  try {
    const response = await fetch(`/api/entries/${encodeURIComponent(key)}`, {
      method: "DELETE"
    });
    const latency = performance.now() - startTime;

    if (response.status === 204 || response.ok) {
      updateResultBox({
        status: "204 No Content",
        foundIn: "Tombstone recorded",
        latencyMs: latency,
        value: null
      });
      addActivityLog("DELETE", `key=${key} -> Tombstone recorded`);
    } else {
      const errText = await response.text();
      updateResultBox({
        status: `${response.status} ${response.statusText}`,
        latencyMs: latency,
        error: errText
      });
      addActivityLog("ERROR", `DELETE key=${key} failed with status ${response.status}`);
    }
  } catch (err) {
    const latency = performance.now() - startTime;
    updateResultBox({
      status: "Network Error",
      latencyMs: latency,
      error: err.message
    });
    addActivityLog("ERROR", `DELETE key=${key} error: ${err.message}`);
  }
}

async function handleInsertRandomKeys() {
  const countInput = document.getElementById("randomKeyCount");
  const count = parseInt(countInput ? countInput.value : 10, 10) || 10;
  const insertBtn = document.getElementById("btnInsertRandom");

  if (insertBtn) {
    insertBtn.disabled = true;
    insertBtn.classList.add("opacity-50", "cursor-not-allowed");
  }

  addActivityLog("BENCHMARK", `Starting insertion of ${count} random keys...`);

  const totalStart = performance.now();
  let successCount = 0;

  for (let i = 1; i <= count; i++) {
    const randomSuffix = Math.floor(100 + Math.random() * 900);
    const key = `key_${randomSuffix}`;
    const value = JSON.stringify({ id: i, data: `sample_value_${randomSuffix}`, ts: Date.now() });

    try {
      const resp = await fetch("/api/entries", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key, value })
      });

      if (resp.ok) {
        successCount++;
        if (count <= 10 || i === 1 || i === count || i % 10 === 0) {
          addActivityLog("PUT", `key=${key} -> Inserted (${i}/${count})`);
        }
      }
    } catch (e) {
      console.error(e);
    }
  }

  const totalDuration = performance.now() - totalStart;

  addActivityLog(
    "BENCHMARK",
    `Completed ${successCount}/${count} keys in ${totalDuration.toFixed(1)} ms (${(totalDuration / count).toFixed(2)} ms/op)`
  );

  updateResultBox({
    status: "201 Batch Completed",
    foundIn: "Storage Engine",
    latencyMs: totalDuration,
    value: `Inserted ${successCount} random keys successfully.`
  });

  if (insertBtn) {
    insertBtn.disabled = false;
    insertBtn.classList.remove("opacity-50", "cursor-not-allowed");
  }
}

function handleCompactionNotImplemented() {
  showNotification("Manual Compaction is yet to be implemented in the engine.", "warning");
  console.log("Action skipped: Compaction is yet to be implemented.");
  addActivityLog("INFO", "Compaction skipped: Action yet to be implemented.");
}

async function checkEngineStatus() {
  const portEl = document.getElementById("enginePort");
  const currentPort = window.location.port || (window.location.protocol === "https:" ? "443" : "80");
  if (portEl) {
    portEl.textContent = `Port: ${currentPort || "7070"}`;
  }

  try {
    const res = await fetch("/");
    const statusDot = document.getElementById("engineStatusDot");
    const statusText = document.getElementById("engineStatusText");
    if (res.ok) {
      if (statusDot) statusDot.className = "w-2 h-2 rounded-full bg-emerald-500";
      if (statusText) statusText.textContent = "Online";
    }
  } catch (e) {
    const statusDot = document.getElementById("engineStatusDot");
    const statusText = document.getElementById("engineStatusText");
    if (statusDot) statusDot.className = "w-2 h-2 rounded-full bg-rose-500";
    if (statusText) statusText.textContent = "Offline";
  }
}

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("btnPut")?.addEventListener("click", handlePut);
  document.getElementById("btnGet")?.addEventListener("click", handleGet);
  document.getElementById("btnDelete")?.addEventListener("click", handleDelete);
  document.getElementById("btnInsertRandom")?.addEventListener("click", handleInsertRandomKeys);
  document.getElementById("btnCompact")?.addEventListener("click", handleCompactionNotImplemented);
  document.getElementById("btnClearLog")?.addEventListener("click", clearLog);

  checkEngineStatus();
  setInterval(checkEngineStatus, 10000);
});

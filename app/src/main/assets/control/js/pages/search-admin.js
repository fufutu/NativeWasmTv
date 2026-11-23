var adminOffset = 0;
var adminLimit = 30;
var adminFilterText = "";
var adminBusy = false;

function adminPost(body, done) {
  api("/api/search/admin", body, done, 20000);
}

function refreshStatus() {
  adminPost({ action: "status" }, function (error, data) {
    if (error) {
      setText("adminStatus", "读取失败");
      setText("statReason", error.message);
      return;
    }
    renderStatus(data || {});
  });
}

function renderStatus(data) {
  setText("adminStatus", data.available ? "索引正常" : "未启用");
  setText("statEngine", data.engine ? "已加载" : "未加载");
  setText("statRows", String(data.rows == null ? 0 : data.rows));
  setText("statGroups", String(data.groups == null ? 0 : data.groups));
  setText("statReason", data.available ? "" : (data.reason || ""));

  var weights = data.fieldWeights || [];
  for (var i = 0; i < weights.length; i++) {
    var field = weights[i];
    if (field.name === "channel_name") setValue("weightChannelName", field.weight);
    else if (field.name === "aliases") setValue("weightAliases", field.weight);
    else if (field.name === "group_title") setValue("weightGroupTitle", field.weight);
  }
  var config = data.config || {};
  if (config.hot_gain != null) setValue("hotGain", config.hot_gain);
  if (config.hot_cap != null) setValue("hotCap", config.hot_cap);
  if (config.rank_mode) setValue("rankMode", config.rank_mode);
  var autoHot = document.getElementById("autoHot");
  if (autoHot) autoHot.checked = config.auto_hot === "1" || config.auto_hot === "true";
}

function rebuildIndex() {
  if (adminBusy) return;
  adminBusy = true;
  setText("adminStatus", "重建中…");
  adminPost({ action: "rebuild" }, function (error, data) {
    adminBusy = false;
    if (error) {
      setText("adminStatus", "重建失败");
      toast(error.message, true);
      return;
    }
    renderStatus(data || {});
    adminOffset = 0;
    loadRows(true);
    toast("索引已重建", false);
  });
}

function saveConfig() {
  var fieldWeights = {
    channel_name: numberValue("weightChannelName", 3),
    aliases: numberValue("weightAliases", 2),
    group_title: numberValue("weightGroupTitle", 1)
  };
  var autoHot = document.getElementById("autoHot");
  adminPost(
    {
      action: "config",
      fieldWeights: fieldWeights,
      autoHot: !!(autoHot && autoHot.checked),
      hotGain: numberValue("hotGain", 0.02),
      hotCap: Math.max(1, Math.round(numberValue("hotCap", 200))),
      rankMode: stringValue("rankMode", "hybrid")
    },
    function (error, data) {
      if (error) {
        toast(error.message, true);
        return;
      }
      renderStatus(data || {});
      toast("排序策略已保存", false);
      adminOffset = 0;
      loadRows(true);
    }
  );
}

function filterRows() {
  var box = document.getElementById("adminFilter");
  var text = ((box && box.value) || "").trim();
  if (text === adminFilterText) return;
  adminFilterText = text;
  adminOffset = 0;
  loadRows(true);
}

function loadMore() {
  loadRows(false);
}

function loadRows(reset) {
  if (adminBusy) return;
  adminBusy = true;
  if (reset) {
    adminOffset = 0;
    setText("adminListSummary", "读取中…");
  }
  adminPost(
    {
      action: "list",
      q: adminFilterText,
      offset: adminOffset,
      limit: adminLimit
    },
    function (error, data) {
      adminBusy = false;
      if (error) {
        setText("adminListSummary", error.message);
        return;
      }
      var items = (data && data.items) || [];
      var host = document.getElementById("adminRows");
      if (!host) return;
      if (reset) host.innerHTML = "";
      if (reset && !items.length) {
        var empty = document.createElement("div");
        empty.className = "search-empty";
        empty.textContent = "没有匹配的频道";
        host.appendChild(empty);
      }
      for (var i = 0; i < items.length; i++) host.appendChild(adminRow(items[i]));
      adminOffset += items.length;
      setText(
        "adminListSummary",
        adminOffset + " / " + String(data.total == null ? adminOffset : data.total)
      );
      setText("adminStatus", "已加载 " + adminOffset + " 条");
      var more = document.getElementById("moreButton");
      if (more) more.disabled = items.length < adminLimit;
    }
  );
}

function adminRow(item) {
  var row = document.createElement("div");
  row.className = "admin-row";
  row.setAttribute("data-key", item.key || "");

  var head = document.createElement("div");
  head.className = "admin-row-head";
  var name = document.createElement("span");
  name.className = "admin-row-name";
  name.textContent = (item.number ? item.number + "  " : "") + (item.name || "");
  var group = document.createElement("span");
  group.className = "admin-row-group";
  group.textContent = (item.group || "") + (item.hotManual == null ? " · 热度自动" : " · 热度手动");
  head.appendChild(name);
  head.appendChild(group);

  var fields = document.createElement("div");
  fields.className = "admin-row-fields";
  fields.appendChild(fieldInput("别名（逗号分隔）", "aliases", item.aliases || "", "text"));
  fields.appendChild(fieldInput("手动权重", "weight", item.weight == null ? 1 : item.weight, "number"));
  fields.appendChild(fieldInput("手动热度", "hotManual", item.hotManual == null ? "" : item.hotManual, "number"));
  fields.appendChild(fieldInput("自动热度", "hot", item.hot == null ? 0 : item.hot, "number", true));

  var foot = document.createElement("div");
  foot.className = "admin-row-foot";
  var toggleLabel = document.createElement("label");
  var toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.setAttribute("data-field", "enabled");
  toggle.checked = item.enabled !== false;
  var toggleText = document.createElement("span");
  toggleText.textContent = "参与搜索";
  toggleLabel.appendChild(toggle);
  toggleLabel.appendChild(toggleText);

  var save = document.createElement("button");
  save.type = "button";
  save.textContent = "保存";
  save.onclick = function () {
    saveRow(row, item.key);
  };

  foot.appendChild(toggleLabel);
  foot.appendChild(save);

  row.appendChild(head);
  row.appendChild(fields);
  row.appendChild(foot);
  return row;
}

function fieldInput(label, field, value, type, readonly) {
  var wrap = document.createElement("label");
  var span = document.createElement("span");
  span.textContent = label;
  var input = document.createElement("input");
  input.type = type || "text";
  input.setAttribute("data-field", field);
  if (type === "number") {
    input.step = field === "weight" ? "0.1" : "1";
  }
  if (value !== undefined && value !== null) input.value = String(value);
  if (readonly) {
    input.readOnly = true;
  } else if (field === "hotManual") {
    input.placeholder = "留空=自动";
  }
  wrap.appendChild(span);
  wrap.appendChild(input);
  return wrap;
}

function saveRow(row, key) {
  if (!key) return;
  var aliases = fieldValue(row, "aliases");
  var weight = parseFloat(fieldValue(row, "weight"));
  if (!isFinite(weight) || weight <= 0) weight = 1;
  var hotManualText = fieldValue(row, "hotManual").trim();
  var hotManual = null;
  if (hotManualText.length) {
    hotManual = Math.round(parseFloat(hotManualText));
    if (!isFinite(hotManual)) hotManual = null;
  }
  var enabledInput = row.querySelector('input[data-field="enabled"]');
  adminPost(
    {
      action: "save",
      key: key,
      aliases: aliases,
      weight: weight,
      hotManual: hotManual,
      enabled: !!(enabledInput && enabledInput.checked)
    },
    function (error) {
      if (error) {
        toast(error.message, true);
        return;
      }
      toast("已保存", false);
      refreshStatus();
    }
  );
}

function fieldValue(row, field) {
  var input = row.querySelector('input[data-field="' + field + '"]');
  return input ? input.value : "";
}

function setText(id, text) {
  var node = document.getElementById(id);
  if (node) node.textContent = text == null ? "" : text;
}

function setValue(id, value) {
  var node = document.getElementById(id);
  if (node) node.value = value == null ? "" : String(value);
}

function stringValue(id, fallback) {
  var node = document.getElementById(id);
  if (!node || !node.value) return fallback;
  return node.value;
}

function numberValue(id, fallback) {
  var node = document.getElementById(id);
  if (!node) return fallback;
  var value = parseFloat(node.value);
  return isFinite(value) ? value : fallback;
}

refreshStatus();
loadRows(true);

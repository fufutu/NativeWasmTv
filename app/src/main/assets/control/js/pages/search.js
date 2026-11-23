var searchBusy = false;

function submitSearch() {
  startSearch(true);
}

function searchOnly() {
  startSearch(false);
}

function setSearchBusy(busy) {
  var playButton = document.getElementById("searchPlayButton");
  var onlyButton = document.getElementById("searchOnlyButton");
  if (playButton) playButton.disabled = busy;
  if (onlyButton) onlyButton.disabled = busy;
}

function startSearch(play) {
  if (searchBusy) return;
  var box = document.getElementById("searchText");
  var text = ((box && box.value) || "").replace(/\s+/g, " ").trim();
  if (!text) {
    toast("请输入频道关键词", true);
    return;
  }
  searchBusy = true;
  setSearchBusy(true);
  var summary = document.getElementById("searchSummary");
  if (summary) summary.textContent = "搜索中…";
  api(
    "/api/search/channel",
    { text: text, play: play, limit: 8 },
    function (error, data) {
      searchBusy = false;
      setSearchBusy(false);
      if (error) {
        if (summary) summary.textContent = error.message;
        renderResults([]);
        toast(error.message, true);
        return;
      }
      var items = (data && data.candidates) || [];
      renderResults(items);
      var text2;
      if (data && data.action === "switch") {
        text2 = "已切换到「" + ((data && data.name) || "") + "」";
      } else if (items.length) {
        text2 = "电视待选：" + items.length + " 个候选，8 秒后自动播放第一个";
      } else {
        text2 = "没有找到匹配的频道";
      }
      if (summary) summary.textContent = text2;
      toast(text2, false);
    },
    15000
  );
}

function renderResults(items) {
  var host = document.getElementById("searchResults");
  if (!host) return;
  host.innerHTML = "";
  if (!items.length) {
    var empty = document.createElement("div");
    empty.className = "search-empty";
    empty.textContent = "没有结果，换个关键词或去设置页加别名";
    host.appendChild(empty);
    return;
  }
  for (var i = 0; i < items.length; i++) host.appendChild(resultItem(items[i]));
}

function resultItem(item) {
  var row = document.createElement("div");
  row.className = "search-result-item";
  var key = item.key || "";
  row.setAttribute("data-playable", key ? "true" : "false");

  var main = document.createElement("div");
  main.className = "search-result-main";
  var name = document.createElement("span");
  name.className = "search-result-name";
  name.textContent = (item.number ? item.number + "  " : "") + (item.name || "");
  var meta = document.createElement("span");
  meta.className = "search-result-meta";
  var parts = [];
  if (item.group) parts.push(item.group);
  if (item.aliases) parts.push("别名 " + item.aliases);
  parts.push("热度 " + (item.hot || 0));
  meta.textContent = parts.join(" · ");
  main.appendChild(name);
  main.appendChild(meta);

  var score = document.createElement("span");
  score.className = "search-result-score";
  score.textContent = key ? "点击播放" : "";

  row.appendChild(main);
  row.appendChild(score);
  if (key) {
    row.onclick = function () {
      playKey(key, item.name || "");
    };
  }
  return row;
}

function playKey(key, name) {
  api(
    "/api/search/play",
    { key: key },
    function (error, data) {
      if (error) {
        toast(error.message, true);
        return;
      }
      toast("已切换到「" + ((data && data.name) || name) + "」", false);
    },
    12000
  );
}

// Each live check is explicit; reading status never sends a message.
export function installDiscordLab(host, { server, request, fail }) {
  const section = document.createElement("section");
  section.className = "discord-lab";
  section.innerHTML = `<h3>Проверки Discord</h3><p>Проверка механизма использует подменённый транспорт. Остальные проверки обращаются к настоящему боту только в отдельной тестовой категории. Кнопки и ответ игрока подтверждаются реальным нажатием и сообщением.</p><p data-config role="status"></p><div class="row" data-actions></div><div data-results></div>`;
  host.append(section);
  const config = section.querySelector("[data-config]"),
    results = section.querySelector("[data-results]");
  function render(data) {
    if ("configured" in data)
      config.textContent =
        `${data.botAvailable ? "Бот доступен" : "Бот недоступен"} · ${data.configured ? `Канал: ${data.channelId}. Тестировщиков: ${data.recipientCount}` : "Настройте preview.discord: category-id, channel-id и allowed-user-ids"}` +
        (data.problems?.length ? ". " + data.problems.join("; ") : "");
    results.replaceChildren();
    for (const check of [...(data.models || []), ...(data.results || [])]) {
      const row = document.createElement("p");
      const state = check.state || (check.passed ? "PASS" : "FAIL");
      row.textContent = `${{ PASS: "✓", FAIL: "✗", WAITING: "⏳", BLOCKED: "○" }[state] || "○"} ${check.name} · ${check.mode === "TRANSPORT_MODEL" ? "Подменённый транспорт" : "Настоящий Discord"} · ${check.detail}`;
      results.append(row);
    }
    for (const item of data.messages || [])
      if (["UNCERTAIN", "FAILED"].includes(item.state)) {
        const row = document.createElement("p");
        row.textContent = `${item.state === "UNCERTAIN" ? "Нужна ручная сверка" : "Отправка отклонена"}: ${item.error}. Не удаляйте сохранённое состояние без проверки канала.`;
        results.append(row);
      }
  }
  for (const [action, text] of [
    ["status", "Обновить результаты"],
    ["model", "Проверить механизм без отправки"],
    ["diagnose", "Проверить подключение и канал"],
    ["panel", "Опубликовать тестовые кнопки"],
    ["dm", "Отправить тестовые ЛС"],
    ["roundtrip", "Проверить канал, изображение и архив"],
  ]) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = text;
    button.onclick = async () => {
      if (!server()) {
        config.textContent =
          "Переключитесь в «Серверный Preview». Локальная модель не проверяет подключение бота и не отправляет сообщения.";
        return;
      }
      for (const other of section.querySelectorAll("button"))
        other.disabled = true;
      try {
        const data = await request(
          action === "status" ? "GET" : "POST",
          action === "status" ? "/api/discord-lab" : "/api/discord-lab/run",
          action === "status" ? undefined : { action },
        );
        render(data);
      } catch (error) {
        config.textContent = error.message;
        fail(error);
      } finally {
        for (const other of section.querySelectorAll("button"))
          other.disabled = false;
      }
    };
    section.querySelector("[data-actions]").append(button);
  }
  config.textContent = server()
    ? "Обновите результаты для проверки настроек. Публикация и ЛС запускаются отдельными кнопками."
    : "Сейчас локальный режим. Для проверок транспорта и настоящего Discord создайте серверную песочницу.";
}

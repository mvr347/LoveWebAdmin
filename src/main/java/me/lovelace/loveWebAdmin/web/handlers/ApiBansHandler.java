package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Управление кастомными банами:
 * GET  /api/bans/my   — Мои баны (созданные текущим админом)
 * GET  /api/bans/all  — Все баны сервера
 * GET  /api/bans/{id} — Детали конкретного бана
 * POST /api/bans      — Создание или оформление черновика бана
 * POST /api/bans/{id}/unban — Разбан
 * DELETE /api/bans/{id} — Удаление черновика/бана
 */
public class ApiBansHandler extends ApiHandlerSupport {

    public ApiBansHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_BANS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/all".equals(pathInfo) || "/".equals(pathInfo)) {
            String filter = req.getParameter("status");
            List<WebBan> bans = plugin.getDatabaseManager().getAllBans(filter);
            sendSuccess(resp, bans.stream().map(this::toBanMap).toList());
            return;
        }

        if ("/reasons".equals(pathInfo)) {
            sendSuccess(resp, loadBanReasons());
            return;
        }

        if ("/appeal-templates".equals(pathInfo)) {
            sendSuccess(resp, loadAppealTemplates());
            return;
        }

        if ("/my".equals(pathInfo)) {
            String admin = sessionOpt.get().adminUsername();
            List<WebBan> bans = plugin.getDatabaseManager().getBansByCreator(admin);
            sendSuccess(resp, bans.stream().map(this::toBanMap).toList());
            return;
        }

        if (pathInfo.startsWith("/")) {
            try {
                int id = Integer.parseInt(pathInfo.substring(1));
                Optional<WebBan> banOpt = plugin.getDatabaseManager().getBanById(id);
                if (banOpt.isPresent()) {
                    sendSuccess(resp, toBanMap(banOpt.get()));
                } else {
                    sendError(resp, 404, "Бан не найден");
                }
                return;
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID бана");
                return;
            }
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_BANS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo != null && pathInfo.contains("/unban")) {
            handleUnban(req, resp, sessionOpt.get());
            return;
        }

        if (pathInfo != null && pathInfo.contains("/attach-report")) {
            handleAttachReport(req, resp, sessionOpt.get());
            return;
        }

        // Создание или оформление бана
        Map<String, Object> body = readJsonBody(req);
        int banId = 0;
        if (body.get("id") != null) {
            try {
                banId = (int) Double.parseDouble(String.valueOf(body.get("id")));
            } catch (NumberFormatException e) {
                banId = 0;
            }
        }

        Integer linkedReportId = null;
        if (body.get("linkedReportId") != null && !String.valueOf(body.get("linkedReportId")).isBlank()) {
            try {
                linkedReportId = (int) Double.parseDouble(String.valueOf(body.get("linkedReportId")));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("[ApiBansHandler] Некорректный linkedReportId: " + body.get("linkedReportId"));
                linkedReportId = null;
            }
        }

        String targetName = stringOrNull(body.get("targetName"));
        String ruleReason = stringOrNull(body.get("ruleReason"));
        String description = stringOrNull(body.get("description"));
        boolean isIpBan = Boolean.TRUE.equals(body.get("isIpBan"));

        // Все баны на сервере бессрочные (permanent)
        long durationSeconds = -1;

        List<String> proofUrls = new ArrayList<>();
        if (body.get("proofUrls") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) proofUrls.add(String.valueOf(o));
            }
        }
        if (body.get("screenshot") != null && !String.valueOf(body.get("screenshot")).isBlank()) {
            proofUrls.add(String.valueOf(body.get("screenshot")));
        }
        if (body.get("screenshotUrl") != null && !String.valueOf(body.get("screenshotUrl")).isBlank()) {
            proofUrls.add(String.valueOf(body.get("screenshotUrl")));
        }

        if (targetName == null || targetName.isBlank() || ruleReason == null || ruleReason.isBlank()) {
            sendError(resp, 400, "Укажите имя игрока и причину (пункт правил)");
            return;
        }

        // Проверка: описание обязательно для всех кроме управляющих ролей
        var roleOpt = plugin.getDatabaseManager().getRoleById(sessionOpt.get().roleId());
        boolean isManagement = roleOpt.isPresent() && (
            roleOpt.get().isOwner() ||
            "Администратор".equalsIgnoreCase(roleOpt.get().name()) ||
            "Управляющий".equalsIgnoreCase(roleOpt.get().name()) ||
            "Руководитель".equalsIgnoreCase(roleOpt.get().name()) ||
            "Куратор".equalsIgnoreCase(roleOpt.get().name())
        );

        if (!isManagement && (description == null || description.trim().isBlank())) {
            sendError(resp, 400, "Для вашей роли описание доказательств является обязательным");
            return;
        }

        if (ruleReason.toLowerCase().contains("другое") && (description == null || description.trim().isBlank())) {
            sendError(resp, 400, "При выборе причины «Другое» обязателен поясняющий комментарий");
            return;
        }

        boolean ok = plugin.getBanManager().finalizeBan(
            banId,
            sessionOpt.get().adminUsername(),
            targetName,
            ruleReason,
            description,
            proofUrls,
            isIpBan,
            durationSeconds,
            linkedReportId
        );

        if (ok) {
            String msg = (linkedReportId != null && linkedReportId > 0)
                ? "Бессрочный бан успешно применён и привязан к жалобе #" + linkedReportId
                : "Бессрочный бан успешно оформлен и применён";
            sendSuccess(resp, Map.of("message", msg));
        } else {
            sendError(resp, 500, "Не удалось оформить бан");
        }
    }

    private void handleAttachReport(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        String pathInfo = req.getPathInfo();
        String[] parts = (pathInfo != null && pathInfo.startsWith("/")) ? pathInfo.substring(1).split("/") : new String[0];
        if (parts.length < 2) {
            sendError(resp, 400, "Некорректный маршрут");
            return;
        }

        int banId;
        try {
            banId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID бана");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        int reportId = 0;
        if (body.get("reportId") != null) {
            try {
                reportId = (int) Double.parseDouble(String.valueOf(body.get("reportId")));
            } catch (NumberFormatException e) {
                reportId = 0;
            }
        }

        if (reportId <= 0) {
            sendError(resp, 400, "Укажите корректный ID жалобы (reportId)");
            return;
        }

        boolean ok = plugin.getDatabaseManager().linkBanAndReport(banId, reportId, session.adminUsername());
        if (ok) {
            plugin.getReportManager().acceptReport(reportId, session.adminUsername(), banId);
            sendSuccess(resp, Map.of("message", "Жалоба #" + reportId + " успешно привязана к бану #" + banId));
        } else {
            sendError(resp, 500, "Не удалось привязать жалобу к бану");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_BANS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo != null && pathInfo.startsWith("/")) {
            try {
                int id = Integer.parseInt(pathInfo.substring(1));
                plugin.getDatabaseManager().deleteBan(id);
                plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Удалил запись бана #" + id);
                sendSuccess(resp, Map.of("message", "Запись удалена"));
                return;
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID бана");
                return;
            }
        }
        sendError(resp, 404, "Не найдено");
    }

    private void handleUnban(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        // Быстрый разбан доступен только для Администраторов (или Управляющего)
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        boolean isAdmin = roleOpt.isPresent() && (roleOpt.get().isOwner() || "Администратор".equalsIgnoreCase(roleOpt.get().name()));
        if (!isAdmin) {
            sendError(resp, 403, "Разбан игроков разрешён только Администраторам сервера");
            return;
        }

        String pathInfo = req.getPathInfo();
        String idStr = pathInfo.replace("/unban", "").replace("/", "");
        try {
            int id = Integer.parseInt(idStr);
            boolean ok = plugin.getBanManager().unban(id, session.adminUsername());
            if (ok) {
                sendSuccess(resp, Map.of("message", "Игрок успешно разблокирован"));
            } else {
                sendError(resp, 404, "Бан не найден");
            }
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID бана");
        }
    }

    private List<Map<String, Object>> loadBanReasons() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "bans_reasons.yml");
            if (!file.exists()) {
                plugin.saveResource("bans_reasons.yml", false);
            }
            if (file.exists()) {
                org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                List<Map<?, ?>> rawList = yaml.getMapList("reasons");
                for (Map<?, ?> item : rawList) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", String.valueOf(item.get("id")));
                    map.put("name", String.valueOf(item.get("name")));
                    map.put("require_comment", Boolean.TRUE.equals(item.get("require_comment")));
                    list.add(map);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка загрузки bans_reasons.yml: " + e.getMessage());
        }

        if (list.isEmpty()) {
            list.add(Map.of("id", "cheats", "name", "Читы", "require_comment", false));
            list.add(Map.of("id", "bug_abuse", "name", "Использование багов", "require_comment", false));
            list.add(Map.of("id", "national_insult", "name", "Оскорбление на почве национальности", "require_comment", false));
            list.add(Map.of("id", "advertising", "name", "Реклама", "require_comment", false));
            list.add(Map.of("id", "griefing", "name", "Гриферство", "require_comment", false));
            list.add(Map.of("id", "modded_client", "name", "Читерские модификации клиента", "require_comment", false));
            list.add(Map.of("id", "anticheat_bypass", "name", "Обход античита", "require_comment", false));
            list.add(Map.of("id", "toxic_behavior", "name", "Токсичное поведение", "require_comment", false));
            list.add(Map.of("id", "multi_account", "name", "Мультиаккаунт", "require_comment", false));
            list.add(Map.of("id", "other", "name", "Другое (с обязательным комментарием)", "require_comment", true));
        }
        return list;
    }

    private Map<String, Object> loadAppealTemplates() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "bans_reasons.yml");
            if (!file.exists()) {
                plugin.saveResource("bans_reasons.yml", false);
            }
            if (file.exists()) {
                org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                if (yaml.isConfigurationSection("appeal_templates")) {
                    var section = yaml.getConfigurationSection("appeal_templates");
                    if (section != null) {
                        if (section.isConfigurationSection("form")) {
                            var formSec = section.getConfigurationSection("form");
                            if (formSec != null) {
                                Map<String, Object> formMap = new LinkedHashMap<>();
                                formMap.put("title", formSec.getString("title", "Стандартная форма подачи апелляции"));
                                formMap.put("header", formSec.getString("header", "📌 ФОРМА ОБЖАЛОВАНИЯ БЛОКИРОВКИ"));
                                formMap.put("fields", formSec.getStringList("fields"));
                                formMap.put("footer", formSec.getString("footer", ""));
                                result.put("form", formMap);
                            }
                        }
                        if (section.isList("verdicts")) {
                            List<Map<?, ?>> rawList = section.getMapList("verdicts");
                            List<Map<String, Object>> verdicts = new ArrayList<>();
                            for (Map<?, ?> item : rawList) {
                                Map<String, Object> vm = new LinkedHashMap<>();
                                vm.put("id", String.valueOf(item.get("id")));
                                vm.put("name", String.valueOf(item.get("name")));
                                vm.put("type", String.valueOf(item.get("type")));
                                vm.put("action_hint", item.get("action_hint") != null ? String.valueOf(item.get("action_hint")) : "");
                                vm.put("template", String.valueOf(item.get("template")));
                                verdicts.add(vm);
                            }
                            result.put("verdicts", verdicts);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка загрузки appeal_templates из bans_reasons.yml: " + e.getMessage());
        }

        // Fallbacks if not configured in YAML
        if (!result.containsKey("form")) {
            Map<String, Object> formMap = new LinkedHashMap<>();
            formMap.put("title", "Стандартная форма подачи апелляции");
            formMap.put("header", "📌 ФОРМА ОБЖАЛОВАНИЯ БЛОКИРОВКИ НА СЕРВЕРЕ");
            formMap.put("fields", List.of(
                "1. Ваш игровой никнейм: {targetName}",
                "2. Номер блокировки (ID бана): #{id}",
                "3. Никнейм заблокировавшего администратора: {creatorName}",
                "4. Указанная причина бана: {ruleReason}",
                "5. Дата и время выдачи блокировки: {createdAt}",
                "6. Срок действия наказания: Бессрочно (Permanent)",
                "7. Доказательства от администрации: {screenshotUrl}",
                "8. Почему вы считаете наказание ошибочным / комментарий игрока:\n   [Опишите ситуацию подробно здесь...]",
                "9. Доказательства невиновности со стороны игрока (скриншоты, видео, откаты):\n   [Укажите ссылки на Imgur, YouTube и т.д.]"
            ));
            formMap.put("footer", "⚠️ Внимание: Срок обжалования составляет 7 дней с момента выдачи блокировки. Ложные сведения могут привести к удвоению наказания.");
            result.put("form", formMap);
        }

        if (!result.containsKey("verdicts") || ((List<?>) result.get("verdicts")).isEmpty()) {
            List<Map<String, Object>> verdicts = new ArrayList<>();
            verdicts.add(Map.of(
                "id", "approved_unban",
                "name", "Одобрено (Ошибочный бан / Разбан)",
                "type", "APPROVE",
                "action_hint", "Снять блокировку",
                "template", "Здравствуйте, {targetName}!\n\nВаша апелляция по блокировке #{id} была повторно рассмотрена главной администрацией.\nВ результате проверки доказательств наказание признано ошибочным.\n\n✅ Вердикт: Блокировка полностью снята, аккаунт разбанен.\nПриносим искренние извинения за доставленные неудобства. Приятной игры на нашем сервере!"
            ));
            verdicts.add(Map.of(
                "id", "approved_amnesty",
                "name", "Одобрено (Амнистия / Чистосердечное)",
                "type", "APPROVE",
                "action_hint", "Амнистировать",
                "template", "Здравствуйте, {targetName}!\n\nВаша апелляция по блокировке #{id} рассмотрена администрацией.\nС учётом вашего чистосердечного признания, содействия и отсутствия грубых нарушений в прошлом принято решение пойти вам навстречу.\n\n⚠️ Вердикт: Блокировка снимается под предупреждение. Повторное нарушение приведёт к бессрочному бану без права на обжалование."
            ));
            verdicts.add(Map.of(
                "id", "rejected_guilty",
                "name", "Отклонено (Вина доказана)",
                "type", "REJECT",
                "action_hint", "Оставить в силе",
                "template", "Здравствуйте, {targetName}!\n\nВаша апелляция по блокировке #{id} рассмотрена.\nПредоставленные администратором доказательства по нарушению пункта «{ruleReason}» являются неоспоримыми и подтверждены старшей модерацией.\n\n❌ Вердикт: Апелляция отклонена. Наказание остаётся в силе в полном объёме."
            ));
            verdicts.add(Map.of(
                "id", "rejected_expired",
                "name", "Отклонено (Истёк срок обжалования)",
                "type", "REJECT",
                "action_hint", "Отклонить по сроку",
                "template", "Здравствуйте, {targetName}!\n\nВаша апелляция по блокировке #{id} отклонена.\nСогласно правилам сервера, срок подачи апелляций и жалоб на администрацию составляет не более 7 дней с момента выдачи наказания.\n\n❌ Вердикт: Срок подачи апелляции истёк. В пересмотре отказано."
            ));
            verdicts.add(Map.of(
                "id", "request_info",
                "name", "Запрос проверки / Отката",
                "type", "INFO",
                "action_hint", "Запросить проверку",
                "template", "Здравствуйте, {targetName}!\n\nВаша апелляция по блокировке #{id} принята на рассмотрение.\nДля вынесения окончательного вердикта вам необходимо предоставить полную видеозапись (откат) за 2 минуты до момента бана либо связаться с куратором в Discord для проверки на стороннее ПО в течение 24 часов.\n\n⏳ Статус: Ожидание дополнительной информации от игрока."
            ));
            result.put("verdicts", verdicts);
        }

        return result;
    }

    private Map<String, Object> toBanMap(WebBan ban) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ban.id());
        map.put("targetName", ban.targetName());
        map.put("targetUuid", ban.targetUuid());
        map.put("targetIp", ban.targetIp());
        map.put("creatorName", ban.creatorName());
        map.put("ruleReason", ban.ruleReason());
        map.put("description", ban.description());
        List<String> proofs = new ArrayList<>();
        if (ban.proofUrls() != null && !ban.proofUrls().isBlank()) {
            try {
                proofs = JsonUtils.fromJsonList(ban.proofUrls(), String.class);
            } catch (Exception e) {
                proofs.add(ban.proofUrls());
            }
        }
        map.put("proofUrls", proofs);
        map.put("screenshotUrl", !proofs.isEmpty() ? proofs.get(0) : null);
        map.put("isIpBan", ban.isIpBan());
        map.put("status", ban.status());
        map.put("durationSeconds", ban.durationSeconds());
        map.put("createdAt", ban.createdAt());
        map.put("expiresAt", ban.expiresAt());
        map.put("linkedReportId", ban.linkedReportId());
        if (ban.linkedReportId() != null && ban.linkedReportId() > 0) {
            Optional<PlayerReport> repOpt = plugin.getDatabaseManager().getReportById(ban.linkedReportId());
            if (repOpt.isPresent()) {
                PlayerReport r = repOpt.get();
                Map<String, Object> repData = new LinkedHashMap<>();
                repData.put("id", r.id());
                repData.put("reporterName", r.reporterName());
                repData.put("reasons", r.reasons());
                repData.put("description", r.description());
                repData.put("createdAt", r.createdAt());
                repData.put("status", r.status());
                map.put("linkedReport", repData);
            }
        }
        return map;
    }
}

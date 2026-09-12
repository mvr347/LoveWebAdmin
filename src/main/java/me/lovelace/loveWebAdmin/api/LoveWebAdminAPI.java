package me.lovelace.loveWebAdmin.api;

import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Публичный Java API интерфейс LoveWebAdmin для интеграции со сторонними плагинами.
 * Доступен через {@link LoveWebAdminAPIProvider#get()} или Bukkit ServicesManager.
 */
public interface LoveWebAdminAPI {

    // === БАНЫ И НАКАЗАНИЯ ===

    /**
     * Проверяет, заблокирован ли игрок в системе LoveWebAdmin.
     */
    boolean isPlayerBanned(String playerName);

    /**
     * Получает активный бан игрока.
     */
    Optional<WebBan> getActiveBan(String playerName);

    /**
     * Выдает перманентный бан игроку.
     *
     * @param target          никнейм нарушителя
     * @param reason          причина / пункт правил
     * @param creator         никнейм инициатора бана
     * @param description     описание нарушения
     * @param proofUrl        ссылка на скриншот / видео
     * @param linkedReportId  ID связанной жалобы (если есть)
     * @return созданный объект бана
     */
    WebBan banPlayer(String target, String reason, String creator, String description, String proofUrl, Integer linkedReportId);

    /**
     * Снимает бан с игрока.
     *
     * @param target    никнейм игрока
     * @param adminName никнейм администратора, снимающего бан
     * @return true если бан был найден и успешно снят
     */
    boolean unbanPlayer(String target, String adminName);

    /**
     * Возвращает список всех активных блокировок.
     */
    List<WebBan> getActiveBans();

    // === ВНУТРИИГРОВЫЕ ЖАЛОБЫ (РЕПОРТЫ) ===

    /**
     * Создает новую внутриигровую жалобу.
     */
    PlayerReport createReport(String reporter, String target, List<String> reasons, String description, boolean isRecent);

    /**
     * Получает список всех ожидающих рассмотрения жалоб.
     */
    List<PlayerReport> getPendingReports();

    /**
     * Получает активные жалобы на конкретного игрока.
     */
    List<PlayerReport> getPendingReportsForTarget(String target);

    /**
     * Рассматривает жалобу.
     *
     * @param reportId    номер жалобы
     * @param status      ACCEPTED, REJECTED, EXPIRED
     * @param resolvedBy  никнейм администратора
     * @param linkedBanId ID бана (если жалоба привела к бану)
     * @return true при успехе
     */
    boolean resolveReport(int reportId, String status, String resolvedBy, Integer linkedBanId);

    // === ЗАМОРОЗКА ИГРОКОВ (FREEZE) ===

    /**
     * Проверяет, заморожен ли игрок администратором.
     */
    boolean isPlayerFrozen(UUID playerUuid);

    /**
     * Замораживает или размораживает игрока.
     */
    void setPlayerFrozen(Player player, boolean freeze, String adminName);

    // === СТАТИСТИКА И СТАФФ АУДИТ ===

    /**
     * Получает сводную статистику игрока из базы данных.
     */
    Optional<Map<String, Object>> getPlayerStats(String playerName);

    /**
     * Логирует действие сотрудника в журнал аудита LoveWebAdmin.
     */
    void logStaffAction(String actor, String action);

    /**
     * Отправляет оповещение через систему исходящих вебхуков.
     */
    void dispatchWebhook(String event, Map<String, Object> data);
}

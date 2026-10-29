package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.preview.*;

/**
 * The sole network entry into isolated runtimes. A preview credential is never a live credential.
 */
public final class ApiPreviewHandler extends ApiHandlerSupport {
  public ApiPreviewHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!plugin.isPreviewEnabled()) {
      sendError(resp, 404, "Preview отключён");
      return;
    }
    try {
      String path = req.getPathInfo();
      if (req.getServletPath().equals("/api/preview-sessions")) {
        var owner = requirePermission(req, resp, Permission.MANAGE_PREVIEW);
        if (owner.isEmpty()) return;
        if (owner.get().adminId() < 1) {
          sendError(resp, 403, "Песочницу создаёт сотрудник с обычной сессией и 2FA");
          return;
        }
        if (req.getMethod().equals("GET")) {
          sendSuccess(resp, plugin.preview().list(owner.get().adminId()));
          return;
        }
        if (req.getMethod().equals("DELETE") && path != null && path.matches("/[a-f0-9]{32}")) {
          plugin.preview().removeOwned(path.substring(1), owner.get().adminId());
          sendSuccess(resp, Map.of());
          return;
        }
        if (!req.getMethod().equals("POST") || path != null && !path.equals("/")) {
          sendError(
              resp,
              405,
              "Используйте POST для создания, GET для списка или DELETE с номером песочницы");
          return;
        }
        me.lovelace.loveWebAdmin.web.MutationRequests.execute(
            plugin.operations(),
            operationActor(owner.get()),
            req,
            resp,
            permission -> hasPermission(owner.get(), Permission.MANAGE_PREVIEW),
            (request, response) -> {
              request.setAttribute("lwa.permission", Permission.MANAGE_PREVIEW.name());
              try {
                sendSuccess(response, plugin.preview().create(owner.get().adminId()));
              } catch (RuntimeException error) {
                throw error;
              } catch (Exception error) {
                throw new java.io.IOException(error);
              }
            });
        return;
      }
      if (path == null || !path.matches("/[a-f0-9]{32}(?:/.*)?")) {
        sendError(resp, 404, "Песочница не найдена");
        return;
      }
      String[] split = path.substring(1).split("/", 2);
      var session = plugin.preview().authorize(split[0], req.getHeader("X-Preview-Session"));
      String route = split.length > 1 ? "/api/" + split[1] : "/api/snapshot";
      if (route.equals("/api/session") && req.getMethod().equals("DELETE")) {
        plugin.preview().remove(session);
        sendSuccess(resp, Map.of());
        return;
      }
      Map<String, Object> body =
          Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod())
              ? readJsonBody(req)
              : Map.of();
      var headers = new HashMap<String, String>();
      var names = req.getHeaderNames();
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        headers.put(name.toLowerCase(Locale.ROOT), req.getHeader(name));
      }
      if ((route.equals("/api/snapshot") || route.equals("/api/delivery-status"))
          && !req.getMethod().equals("GET")) {
        sendError(resp, 405, "Используйте GET");
        return;
      }
      Object result =
          route.equals("/api/snapshot")
              ? session.runtime().snapshot()
              : route.equals("/api/delivery-status")
                  ? session.runtime().deliveryStatus()
                  : session.runtime().execute(req.getMethod(), route, body, headers);
      var response = new LinkedHashMap<String, Object>();
      response.put("success", true);
      response.put("data", result);
      response.put("previewState", session.runtime().snapshot());
      sendJson(resp, 200, me.lovelace.loveWebAdmin.utils.JsonUtils.toJson(response));
    } catch (PreviewRuntime.Rejected error) {
      sendError(resp, error.status, error.getMessage());
    } catch (me.lovelace.loveWebAdmin.utils.ActionRejectedException error) {
      sendError(resp, 409, error.getMessage());
    } catch (PayloadTooLargeException error) {
      sendError(resp, 413, "Тело запроса слишком велико");
    } catch (IllegalArgumentException error) {
      sendError(resp, 400, "Некорректные данные Preview");
    } catch (Exception error) {
      plugin.getLogger().warning("Preview: " + error.getClass().getSimpleName());
      sendError(resp, 500, "Не удалось выполнить действие песочницы");
    }
  }
}

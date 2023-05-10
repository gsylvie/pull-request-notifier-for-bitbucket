package se.bjurr.prnfb.presentation;

import static com.google.common.base.Strings.isNullOrEmpty;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.status;
import static se.bjurr.prnfb.transformer.ButtonTransformer.toButtonDto;
import static se.bjurr.prnfb.transformer.ButtonTransformer.toButtonDtoList;
import static se.bjurr.prnfb.transformer.ButtonTransformer.toPrnfbButton;
import static se.bjurr.prnfb.transformer.ButtonTransformer.toTriggerResultDto;

import com.atlassian.annotations.security.XsrfProtectionExcluded;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import se.bjurr.prnfb.http.NotificationResponse;
import se.bjurr.prnfb.presentation.dto.ButtonDTO;
import se.bjurr.prnfb.presentation.dto.ButtonFormElementDTO;
import se.bjurr.prnfb.presentation.dto.ButtonPressDTO;
import se.bjurr.prnfb.service.ButtonsService;
import se.bjurr.prnfb.service.PrnfbRenderer.ENCODE_FOR;
import se.bjurr.prnfb.service.PrnfbRendererWrapper;
import se.bjurr.prnfb.service.SettingsService;
import se.bjurr.prnfb.service.UserCheckService;
import se.bjurr.prnfb.settings.PrnfbButton;
import se.bjurr.prnfb.settings.USER_LEVEL;

@Path("/settings/buttons")
public class ButtonServlet {

  private final ButtonsService buttonsService;
  private final SettingsService settingsService;
  private final UserCheckService userCheckService;

  public ButtonServlet(
      ButtonsService buttonsService,
      SettingsService settingsService,
      UserCheckService userCheckService) {
    this.buttonsService = buttonsService;
    this.settingsService = settingsService;
    this.userCheckService = userCheckService;
  }

  @POST
  @XsrfProtectionExcluded
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  public Response create(ButtonDTO buttonDto) {
    final USER_LEVEL adminRestriction =
        settingsService.getPrnfbSettingsData().getAdminRestriction();
    if (!userCheckService.isAdminAllowed(buttonDto, adminRestriction)) {
      return status(UNAUTHORIZED) //
          .build();
    }

    final PrnfbButton prnfbButton = toPrnfbButton(buttonDto);
    final PrnfbButton created = settingsService.addOrUpdateButton(prnfbButton);
    final ButtonDTO createdDto = toButtonDto(created);

    return status(OK) //
        .entity(createdDto) //
        .build();
  }

  @DELETE
  @Path("{uuid}")
  @XsrfProtectionExcluded
  @Produces(APPLICATION_JSON)
  public Response delete(@PathParam("uuid") UUID prnfbButtonUuid) {
    final PrnfbButton prnfbButton = settingsService.getButton(prnfbButtonUuid);
    final USER_LEVEL adminRestriction =
        settingsService.getPrnfbSettingsData().getAdminRestriction();
    if (!userCheckService.isAdminAllowed(prnfbButton, adminRestriction)) {
      return status(UNAUTHORIZED) //
          .build();
    }
    settingsService.deleteButton(prnfbButtonUuid);
    return status(OK).build();
  }

  @GET
  @Produces(APPLICATION_JSON)
  public Response get() {
    final List<PrnfbButton> buttons = settingsService.getButtons();
    final Iterable<PrnfbButton> allowedButtons = userCheckService.filterAdminAllowed(buttons);
    final List<ButtonDTO> dtos = toButtonDtoList(allowedButtons);
    Collections.sort(dtos);
    return ok(dtos, APPLICATION_JSON).build();
  }

  public static Long parseLong(String s, long defaultVal) {
    s = s != null ? s.trim() : "";
    if ("".equals(s)) {
      return defaultVal;
    }
    try {
      return Long.parseLong(s);
    } catch (RuntimeException e) {
      return defaultVal;
    }
  }

  private static String[] parsePath(String path, String finalLabel) {
    String[] components = path.split("/+");
    String prevString = null;
    String project = null;
    String repo = null;
    String last = null;
    for (String s : components) {
      s = s != null ? s.trim() : "";
      if ("users".equals(prevString)) {
        if (project == null) {
          project = "~" + s.toUpperCase(Locale.ENGLISH);
        }
      } else if ("projects".equalsIgnoreCase(prevString)) {
        if (project == null) {
          project = s;
        }
      } else if ("repos".equalsIgnoreCase(prevString)) {
        if (repo == null) {
          repo = s;
        }
      } else if (finalLabel != null && finalLabel.equalsIgnoreCase(prevString)) {
        if (last == null) {
          last = s;
        }
      }
      prevString = s;
    }
    return new String[] {project, repo, last};
  }

  @GET
  @Path("/{s:.*}")
  @Produces(APPLICATION_JSON)
  public Response getAllPaths(@Context UriInfo ui) {
    final String path = ui.getPath();
    String[] parsed = parsePath(path, "pull-requests");
    String project = parsed[0];
    String repo = parsed[1];
    String pr = parsed[2];
    String uuid = null;
    if (project == null && repo == null && pr == null) {
      parsed = parsePath(path, "buttons");
      if (parsed[2] != null
          && parsed[2].trim().length() == "53076b81-aeec-4159-a81f-8d2ad2ecb4be".length()) {
        uuid = parsed[2];
      }
    }

    if (uuid != null) {
      UUID u = UUID.fromString(uuid);
      return getUuidButtons(u);
    }

    Project p;
    Repository r = null;
    if (project != null) {
      if (repo != null) {
        r = userCheckService.getRepo(project, repo);
        if (r != null && pr == null) {
          // If pr==null then we just want the repoButtons
          return getRepoButtons(r);
        }
      } else {
        // If r==null, then we just want the projectButtons
        p = userCheckService.getProject(project);
        return getProjectButtons(p);
      }
    }

    // Nothing is null - we want the pullRequestButtons
    Integer rId = r != null ? r.getId() : null;
    Long prId = parseLong(pr, -1L);
    return getPullRequestButtons(rId, prId);
  }

  // @Path("/projectKey/{projectKey}")
  public Response getProjectButtons(Project p) {
    final List<PrnfbButton> buttons = settingsService.getButtons(p);
    final Iterable<PrnfbButton> allowedButtons = userCheckService.filterAdminAllowed(buttons);
    final List<ButtonDTO> dtos = toButtonDtoList(allowedButtons);
    Collections.sort(dtos);
    return ok(dtos, APPLICATION_JSON).build();
  }

  // @Path("/projectKey/{projectKey}/repositorySlug/{repositorySlug}")
  public Response getRepoButtons(Repository r) {
    final List<PrnfbButton> buttons = settingsService.getButtons(r);
    final Iterable<PrnfbButton> allowedButtons = userCheckService.filterAdminAllowed(buttons);
    final List<ButtonDTO> dtos = toButtonDtoList(allowedButtons);
    Collections.sort(dtos);
    return ok(dtos, APPLICATION_JSON).build();
  }

  // @Path("{uuid}")
  public Response getUuidButtons(UUID uuid) {
    final PrnfbButton button = settingsService.getButton(uuid);
    final USER_LEVEL adminRestriction =
        settingsService.getPrnfbSettingsData().getAdminRestriction();
    if (!userCheckService.isAdminAllowed(button, adminRestriction)) {
      return status(UNAUTHORIZED).build();
    }
    final ButtonDTO dto = toButtonDto(button);
    return ok(dto, APPLICATION_JSON).build();
  }

  // @Path("/repository/{repositoryId}/pullrequest/{pullRequestId}")
  public Response getPullRequestButtons(Integer repositoryId, Long pullRequestId) {
    final List<PrnfbButton> buttons = buttonsService.getButtons(repositoryId, pullRequestId);
    final List<ButtonDTO> dtos = toButtonDtoList(buttons);
    Collections.sort(dtos);
    for (final ButtonDTO dto : dtos) {
      renderButtonDtoList(repositoryId, pullRequestId, dto);
    }
    return ok(dtos, APPLICATION_JSON).build();
  }

  @POST
  @Path("/fromUUID/{s:.*}")
  @XsrfProtectionExcluded
  @Produces(APPLICATION_JSON)
  public Response press(
      @Context HttpServletRequest request, @Context UriInfo ui, @FormParam("form") String form) {

    final String path = ui.getPath();
    String[] parsed = parsePath(path, "pull-requests");
    String project = parsed[0];
    String repo = parsed[1];
    String pr = parsed[2];

    String[] parsed2 = parsed = parsePath(path, "uuid");
    String uuid = parsed2[2];
    final UUID u = uuid != null ? UUID.fromString(uuid) : null;

    Repository r = userCheckService.getRepo(project, repo);
    Integer rId = r != null ? r.getId() : null;
    Long prId = parseLong(pr, -1L);
    final List<PrnfbButton> buttons = buttonsService.getButtons(rId, prId);
    final Optional<PrnfbButton> button = Iterables.tryFind(buttons, (b) -> b.getUuid().equals(u));
    if (!button.isPresent()) {
      return status(NOT_FOUND).build();
    }
    final List<NotificationResponse> results = buttonsService.handlePressed(rId, prId, u, form);
    final ButtonPressDTO dto = toTriggerResultDto(button.get(), results);
    return ok(dto, APPLICATION_JSON).build();
  }

  private void renderButtonDtoList(Integer repositoryId, Long pullRequestId, ButtonDTO dto) {
    final PrnfbRendererWrapper renderer =
        buttonsService.getRenderer(repositoryId, pullRequestId, dto.getUuid());

    final List<ButtonFormElementDTO> buttonFormDtoList = dto.getButtonFormList();
    if (buttonFormDtoList != null) {
      for (final ButtonFormElementDTO buttonFormElementDto : buttonFormDtoList) {
        final String defaultValue = buttonFormElementDto.getDefaultValue();
        if (!isNullOrEmpty(defaultValue)) {
          final String defaultValueRendered = renderer.render(defaultValue, ENCODE_FOR.NONE);
          buttonFormElementDto.setDefaultValue(defaultValueRendered);
        }
      }
      dto.setButtonFormList(buttonFormDtoList);
    }

    final String redirectUrl = dto.getRedirectUrl();
    if (!isNullOrEmpty(redirectUrl)) {
      final String redirectUrlRendered = renderer.render(redirectUrl, ENCODE_FOR.HTML);
      dto.setRedirectUrl(redirectUrlRendered);
    }
  }
}

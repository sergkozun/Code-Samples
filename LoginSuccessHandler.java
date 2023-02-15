package pc.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import pc.entity.Client;
import pc.entity.User;
import pc.service.AppService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppService appService;

    private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {

        User user = (User) authentication.getPrincipal();
        Client client = user.getClient();
        boolean firstSignIn = authentication instanceof UsernamePasswordAuthenticationToken;

        if (client.getClientType().name().equals("Corporate")) {
            if (new HashSet<>(Arrays.asList(68L, 77L, 80L, 84L)).contains(client.getOwnerId())) {
                if (appService.isUserAllowed(user)) {
                    if (user.isActive()) {
                        request.getSession().setAttribute("user", user);
                        request.getSession().setAttribute("roles", appService.getRoleMenuItems(user.getRole()));
                        log.info(String.format("User \"%s\" with id - %d (client - %d): successful sign in!", user.getLogin(), user.getId(), client.getId()));

                        if (firstSignIn) {
                            Properties success = new Properties();
                            success.setProperty("success", "true");
                            success.setProperty("login", user.getLogin());
                            objectMapper.writeValue(response.getOutputStream(), success);
                        } else {
                            String queryString = request.getQueryString();
                            response.sendRedirect(request.getRequestURI() + (queryString != null ? "?" + queryString : ""));
                        }
                    } else {
                        log.info(String.format("User \"%s\" with id - %d: failed sign in attempt, client - %d is not active!", user.getLogin(), user.getId(), client.getId()));
                        throw new LoginFailureException("clientBlockedText");
                    }
                } else {
                    log.info(String.format("User \"%s\" with id - %d: failed sign in attempt, user is not allowed!", user.getLogin(), user.getId()));
                    throw new LoginFailureException("clientOutOfServiceText");
                }
            } else {
                log.info(String.format("User \"%s\" with id - %d: failed sign in attempt, client - %d is not a client of Neftika!", user.getLogin(), user.getId(), client.getId()));
                throw new LoginFailureException("pcOnlyForNeftikaText");
            }
        } else {
            log.info(String.format("User \"%s\" with id - %d: failed sign in attempt, client - %d is not a corporate client!", user.getLogin(), user.getId(), client.getId()));
            throw new LoginFailureException("pcOnlyForLegalEntityText");
        }
    }
}

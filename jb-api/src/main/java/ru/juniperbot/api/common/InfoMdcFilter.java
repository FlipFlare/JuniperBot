/*
 * This file is part of JuniperBot.
 *
 * JuniperBot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBot. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.juniperbot.api.common;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.MDC;
import ru.juniperbot.api.security.model.DiscordUserDetails;
import ru.juniperbot.api.security.utils.SecurityUtils;

import javax.servlet.*;
import java.io.IOException;

public class InfoMdcFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            MDC.put("requestId", RandomStringUtils.random(8, "0123456789abcdef"));
            DiscordUserDetails details = SecurityUtils.getCurrentUser();
            if (details != null) {
                MDC.put("userId", details.getId());
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("userId");
        }
    }

    @Override
    public void destroy() {
    }
}

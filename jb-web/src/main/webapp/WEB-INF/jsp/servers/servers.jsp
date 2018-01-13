<%--
This file is part of JuniperBotJ.

JuniperBotJ is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

JuniperBotJ is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with JuniperBotJ. If not, see <http://www.gnu.org/licenses/>.
--%>
<%@ page contentType="text/html;charset=UTF-8" %>
<%@include file="/WEB-INF/template/include.jsp" %>

<div class="container">
    <div class="row server-select">
        <div class="col-md-6 col-md-offset-3 text-center">
            <c:if test="${empty servers}">
                <h3>У вас нет ни одного сервера, где вы могли бы настроить Джупи.</h3>
                <h4>Создайте свой сервер прямо сейчас и <a href="<c:url value="/" />">добавьте</a> Джупи к себе!</h4>
            </c:if>
            <c:if test="${not empty servers}">
                <h3>Выберите <img src="<c:url value="/resources/img/discord-logo.svg"/>" class="discord-logo"> сервер:</h3>
            </c:if>
        </div>
    </div>

    <div class="row row-centered server-list">
        <c:forEach var="server" items="${servers}">
            <div class="col-md-2 text-center col-centered">
                <a href="<c:url value="/config/${server.id}" />">
                    <div class="server-item-wrapper">
                        <img src="${server.avatarUrl}" class="img-circle server-item" title="${server.name}" data-toggle="tooltip" data-container="body" data-placement="bottom" />
                    </div>
                </a>
            </div>
        </c:forEach>
    </div>
</div>
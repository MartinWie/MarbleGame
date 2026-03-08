package de.mw

import io.github.martinwie.htmx.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*

internal fun Route.registerPageRoutes() {
    // Home page - create or join a game
    get("/") {
        val session = call.sessions.get<UserSession>()
        val savedName = session?.playerName?.take(MAX_PLAYER_NAME_LENGTH) ?: ""
        val error = call.request.queryParameters["error"]
        val lang = call.getLanguage()

        call.respondHtml {
            basePage(
                title = "home.title".t(lang),
                lang = lang,
                includeHtmx = true,
                extraBodyContent = {
                    script(src = "/static/home.js") {}
                    script {
                        unsafe { +"initHomePage();" }
                    }
                },
            ) {
                h1 { +"home.title".t(lang) }

                if (error == "game_not_found") {
                    div("error-message") {
                        +"error.gameNotFound".t(lang)
                    }
                }

                div("card mode-selector-card") {
                    h2 { +"home.chooseMode".t(lang) }
                    p("hint") { +"home.chooseModeHint".t(lang) }

                    div("mode-grid") {
                        button(type = ButtonType.button, classes = "mode-tile") {
                            attributes["data-mode"] = "marbles"
                            attributes["aria-pressed"] = "false"
                            attributes["aria-controls"] = "create-form-marbles"
                            attributes["onclick"] =
                                """
                                document.querySelectorAll('.mode-tile').forEach(function(x){x.classList.remove('active');});
                                document.querySelectorAll('.mode-tile').forEach(function(x){x.setAttribute('aria-pressed','false');});
                                this.classList.add('active');
                                this.setAttribute('aria-pressed','true');
                                document.getElementById('create-form-marbles').classList.add('active');
                                document.getElementById('create-form-chess').classList.remove('active');
                                document.getElementById('create-mode-placeholder').classList.remove('active');
                                """.trimIndent().replace("\n", " ")
                            span("mode-icon") { +"●●" }
                            span("mode-title") { +"home.mode.marbles".t(lang) }
                            span("mode-hint") { +"home.createGameHint".t(lang) }
                        }

                        button(type = ButtonType.button, classes = "mode-tile") {
                            attributes["data-mode"] = "chess"
                            attributes["aria-pressed"] = "false"
                            attributes["aria-controls"] = "create-form-chess"
                            attributes["onclick"] =
                                """
                                document.querySelectorAll('.mode-tile').forEach(function(x){x.classList.remove('active');});
                                document.querySelectorAll('.mode-tile').forEach(function(x){x.setAttribute('aria-pressed','false');});
                                this.classList.add('active');
                                this.setAttribute('aria-pressed','true');
                                document.getElementById('create-form-marbles').classList.remove('active');
                                document.getElementById('create-form-chess').classList.add('active');
                                document.getElementById('create-mode-placeholder').classList.remove('active');
                                """.trimIndent().replace("\n", " ")
                            span("mode-icon") { +"♚" }
                            span("mode-title") { +"home.mode.chess".t(lang) }
                            span("mode-hint") { +"home.createChessHint".t(lang) }
                        }
                    }

                    div("create-mode-forms") {
                        p("hint create-mode-placeholder active") {
                            id = "create-mode-placeholder"
                            +"home.pickGameFirst".t(lang)
                        }

                        form {
                            id = "create-form-marbles"
                            classes = setOf("mode-form")
                            hxPost("/game/create")
                            hxTarget("body")

                            div("form-group") {
                                label { +"home.yourName".t(lang) }
                                input(type = InputType.text, name = "playerName") {
                                    required = true
                                    placeholder = "home.namePlaceholder".t(lang)
                                    value = savedName
                                    maxLength = MAX_PLAYER_NAME_LENGTH.toString()
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"button.create".t(lang) }
                        }

                        form {
                            id = "create-form-chess"
                            classes = setOf("mode-form")
                            hxPost("/chess/create")
                            hxTarget("body")

                            div("form-group") {
                                label { +"home.yourName".t(lang) }
                                input(type = InputType.text, name = "playerName") {
                                    required = true
                                    placeholder = "home.namePlaceholder".t(lang)
                                    value = savedName
                                    maxLength = MAX_PLAYER_NAME_LENGTH.toString()
                                }
                            }
                            div("option-row") {
                                label("option-checkbox") {
                                    input(type = InputType.checkBox, name = "timedMode") {
                                        id = "timed-mode"
                                        attributes["onchange"] =
                                            "document.getElementById('timed-config-wrap').classList.toggle('hidden', !this.checked);"
                                    }
                                    span("option-slider") {}
                                    span("option-copy") {
                                        span("option-title") { +"home.chess.option.timed".t(lang) }
                                        span("option-subtitle") { +"home.chess.option.timed.hint".t(lang) }
                                    }
                                }
                            }
                            div {
                                id = "timed-config-wrap"
                                classes = setOf("option-row", "timed-config", "hidden")
                                label {
                                    htmlFor = "clock-minutes"
                                    +"home.chess.option.clockMinutes".t(lang)
                                }
                                input(type = InputType.number, name = "clockMinutes") {
                                    id = "clock-minutes"
                                    min = "1"
                                    max = "60"
                                    step = "1"
                                    value = "5"
                                    attributes["inputmode"] = "numeric"
                                }
                            }
                            div("option-row") {
                                label("option-checkbox") {
                                    input(type = InputType.checkBox, name = "streamerMode") { id = "streamer-mode" }
                                    span("option-slider") {}
                                    span("option-copy") {
                                        span("option-title") { +"home.chess.option.streamer".t(lang) }
                                        span("option-subtitle") { +"home.chess.option.streamer.hint".t(lang) }
                                    }
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn btn-primary") { +"button.createChess".t(lang) }
                        }
                    }
                }
            }
        }
    }

    // Imprint page
    get("/imprint") {
        val lang = call.getLanguage()
        call.respondHtml {
            basePage("${"footer.imprint".t(lang)} - ${"game.title".t(lang)}", lang) {
                h1 { +"footer.imprint".t(lang) }
                div("card") {
                    style = "text-align: left;"
                    h2 { +"imprint.headline".t(lang) }
                    p { +"imprint.hobbyProject".t(lang) }
                    h3 { +"imprint.contact".t(lang) }
                    p { +"imprint.email".t(lang) }
                    h3 { +"imprint.liabilityContent".t(lang) }
                    p { +"imprint.liabilityContentText".t(lang) }
                    h3 { +"imprint.liabilityLinks".t(lang) }
                    p { +"imprint.liabilityLinksText".t(lang) }
                }
            }
        }
    }

    // Privacy policy page
    get("/privacy") {
        val lang = call.getLanguage()
        call.respondHtml {
            basePage("${"footer.privacy".t(lang)} - ${"game.title".t(lang)}", lang) {
                h1 { +"footer.privacy".t(lang) }
                div("card") {
                    style = "text-align: left;"
                    h2 { +"privacy.headline".t(lang) }

                    h3 { +"privacy.controller".t(lang) }
                    p { +"privacy.controllerText".t(lang) }
                    p { +"privacy.controllerEmail".t(lang) }
                    p { +"privacy.hobbyProject".t(lang) }

                    h3 { +"privacy.dataCollected".t(lang) }
                    p { +"privacy.dataCollectedText".t(lang) }
                    ul {
                        li { +"privacy.dataSession".t(lang) }
                        li { +"privacy.dataPlayer".t(lang) }
                        li { +"privacy.dataAnalytics".t(lang) }
                    }

                    h3 { +"privacy.legalBasis".t(lang) }
                    p { +"privacy.legalBasisText".t(lang) }
                    ul {
                        li { +"privacy.legalBasisNecessary".t(lang) }
                        li { +"privacy.legalBasisConsent".t(lang) }
                    }

                    h3 { +"privacy.retention".t(lang) }
                    p { +"privacy.retentionText".t(lang) }

                    h3 { +"privacy.rights".t(lang) }
                    p { +"privacy.rightsText".t(lang) }
                    ul {
                        li { +"privacy.rightsAccess".t(lang) }
                        li { +"privacy.rightsRectification".t(lang) }
                        li { +"privacy.rightsErasure".t(lang) }
                        li { +"privacy.rightsRestriction".t(lang) }
                        li { +"privacy.rightsWithdraw".t(lang) }
                        li { +"privacy.rightsComplaint".t(lang) }
                    }

                    h3 { +"privacy.cookies".t(lang) }
                    p { +"privacy.cookiesText".t(lang) }

                    h3 { +"privacy.thirdParties".t(lang) }
                    p { +"privacy.thirdPartiesText".t(lang) }
                }
            }
        }
    }
}

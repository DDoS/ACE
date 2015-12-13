/*
 * This file is part of ACE, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015-2015 Aleksi Sapon <http://sapon.ca/JICI/>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ca.sapon.ace;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.inject.Inject;
import org.slf4j.Logger;

import org.spongepowered.api.Game;
import org.spongepowered.api.command.CommandCallable;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameAboutToStartServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.Texts;
import org.spongepowered.api.text.format.TextColors;

import ca.sapon.jici.SourceException;
import ca.sapon.jici.SourceMetadata;
import ca.sapon.jici.SourceMetadata.ErrorInformation;
import ca.sapon.jici.decoder.Decoder;
import ca.sapon.jici.evaluator.Environment;
import ca.sapon.jici.evaluator.Environment.Variable;
import ca.sapon.jici.evaluator.type.ClassType;
import ca.sapon.jici.evaluator.type.Type;
import ca.sapon.jici.evaluator.value.ObjectValue;
import ca.sapon.jici.evaluator.value.Value;
import ca.sapon.jici.lexer.Identifier;
import ca.sapon.jici.lexer.Lexer;
import ca.sapon.jici.lexer.Token;
import ca.sapon.jici.lexer.TokenID;
import ca.sapon.jici.parser.Parser;
import ca.sapon.jici.parser.expression.Expression;
import ca.sapon.jici.parser.statement.Statement;

@Plugin(id = "ACE", name = "ACE", version = ACE.VERSION)
public class ACE {
    public static final String VERSION = "0.0.1";
    private static final int ENTRIES_PER_PAGE = 5;
    @Inject
    private Logger logger;
    @Inject
    private Game game;
    @Inject
    private PluginContainer instance;
    private final Map<CommandSource, ACEUser> users = new WeakHashMap<>();

    @Listener
    public void onServerStarting(GameAboutToStartServerEvent event) {
        final Game game = event.getGame();
        final CommandManager commandDispatcher = game.getCommandManager();
        commandDispatcher.register(instance, new ACEEval(), "acee");
        commandDispatcher.register(instance, new ACEContext(), "acec");
        logger.info("Loaded ACE v{}", VERSION);
    }

    private ACEUser getUser(CommandSource source) {
        ACEUser user = users.get(source);
        if (user == null) {
            user = new ACEUser(source);
            users.put(source, user);
        }
        return user;
    }

    private class ACEEval implements CommandCallable {
        @Override
        public List<String> getSuggestions(CommandSource source, String arguments) throws CommandException {
            return Collections.emptyList();
        }

        @Override
        public boolean testPermission(CommandSource source) {
            return source.hasPermission("ace.eval");
        }

        @Override
        public Optional<? extends Text> getShortDescription(CommandSource source) {
            return Optional.of((Text) Texts.of("Evaluates Java expressions and statement"));
        }

        @Override
        public Optional<? extends Text> getHelp(CommandSource source) {
            return Optional.of(getUsage(source));
        }

        @Override
        public Text getUsage(CommandSource source) {
            return Texts.of("acee expression | statement");
        }

        @Override
        public CommandResult process(CommandSource source, String argumentString) throws CommandException {
            getUser(source).eval(argumentString);
            return CommandResult.success();
        }
    }

    private class ACEContext implements CommandCallable {
        @Override
        public List<String> getSuggestions(CommandSource source, String arguments) throws CommandException {
            return Collections.emptyList();
        }

        @Override
        public boolean testPermission(CommandSource source) {
            return source.hasPermission("ace.context");
        }

        @Override
        public Optional<Text> getShortDescription(CommandSource source) {
            return Optional.of((Text) Texts.of("Manage the source's ACE context"));
        }

        @Override
        public Optional<Text> getHelp(CommandSource source) {
            return Optional.of(getUsage(source));
        }

        @Override
        public Text getUsage(CommandSource source) {
            return Texts.of("imports [page] | variables [page] | reset");
        }

        @Override
        public CommandResult process(CommandSource source, String argumentString) throws CommandException {
            final String[] arguments = argumentString.split(" ");
            final String command = arguments[0];
            final int page = arguments.length >= 2 ? parseIntWithDefault(arguments[1], -1) : 1;
            if (command.equals("imports")) {
                getUser(source).printImports(page);
                return CommandResult.success();
            }
            if (command.equals("variables")) {
                getUser(source).printVariables(page);
                return CommandResult.success();
            }
            if (command.equals("reset")) {
                getUser(source).resetEnvironment();
                return CommandResult.success();
            }
            throw new CommandException(Texts.of("Unknown command: ", command));
        }

        private int parseIntWithDefault(String string, int _default) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException exception) {
                return _default;
            }
        }
    }

    private class ACEUser {
        private final CommandSource user;
        private Environment environment;
        private StringBuilder sourceBuffer = null;

        public ACEUser(CommandSource user) {
            this.user = user;
        }

        private void eval(String code) {
            if (environment == null) {
                createEnvironment();
            }
            if (code.endsWith("#")) {
                if (sourceBuffer == null) {
                    sourceBuffer = new StringBuilder();
                }
                sourceBuffer.append(code.substring(0, code.length() - 1));
                sendACEMessage(user, Texts.of(TextColors.DARK_GREEN, "Buffered code"));
                return;
            }
            if (sourceBuffer != null) {
                code = sourceBuffer.append(code).toString();
                sourceBuffer = null;
            }
            final SourceMetadata metadata = new SourceMetadata(code);
            try {
                code = Decoder.decode(code, metadata);
                final List<Token> tokens = Lexer.lex(code);
                if (tokens.isEmpty()) {
                    sendACEMessage(user, Texts.of(TextColors.DARK_RED, "Nothing to evaluate"));
                    return;
                }
                if (tokens.get(tokens.size() - 1).getID() == TokenID.SYMBOL_SEMICOLON) {
                    final List<Statement> statements = Parser.parse(tokens);
                    for (Statement statement : statements) {
                        statement.execute(environment);
                    }
                    sendACEMessage(user, Texts.of(TextColors.DARK_GREEN, "Success"));
                } else {
                    final Expression expression = Parser.parseExpression(tokens);
                    final Type type = expression.getType(environment);
                    final Value value = expression.getValue(environment);
                    sendACEMessage(user, Texts.of(
                            TextColors.DARK_GREEN, "Type: ", TextColors.RESET, type.getName(),
                            TextColors.DARK_GREEN, " Value: ", TextColors.RESET, value.asString()
                    ));
                }
            } catch (SourceException exception) {
                displayError(metadata.generateErrorInformation(exception));
            } catch (Exception exception) {
                sendACEMessage(user, Texts.of(TextColors.DARK_RED, "Unknown exception, see console"));
                logger.error("Error while evaluating code", exception);
            }
        }

        private void createEnvironment() {
            environment = new Environment();
            addVariable("game", game);
            addVariable("me", user);
            addVariable("printer", new Printer(user));
        }

        private void addVariable(String name, Object variable) {
            environment.declareVariable(Identifier.from(name, 0), ClassType.of(variable.getClass()), ObjectValue.of(variable));
        }

        private void displayError(ErrorInformation error) {
            sendACEMessage(user, Texts.of(TextColors.DARK_RED, error.getMessage()));
            final String line = error.getLine() + " ";
            final int start = error.getStartIndex();
            final int end = error.getEndIndex() + 1;
            String problem = line.substring(start, end);
            if (problem.length() == 1 && Character.isWhitespace(problem.charAt(0))) {
                problem = "_";
            }
            sendACEMessage(user, Texts.of(
                    line.substring(0, start),
                    TextColors.DARK_RED, problem,
                    TextColors.RESET, line.substring(end)
            ));
        }

        private void printImports(int page) {
            if (validateInfoArguments(page)) {
                printEntries(filterJavaLangClasses(environment.getClasses()), page);
            }
        }

        private Collection<Class<?>> filterJavaLangClasses(Collection<Class<?>> classes) {
            final Set<Class<?>> filtered = new HashSet<>();
            for (Class<?> _class : classes) {
                final String name = _class.getCanonicalName();
                if (!name.startsWith("java.lang.") || name.indexOf('.', 10) != -1) {
                    filtered.add(_class);
                }
            }
            return filtered;
        }

        private void printVariables(int page) {
            if (validateInfoArguments(page)) {
                printEntries(environment.getVariables(), page);
            }
        }

        private boolean validateInfoArguments(int page) {
            if (environment == null) {
                sendACEMessage(user, Texts.of(TextColors.DARK_RED, "No active context"));
                return false;
            }
            if (page < 1) {
                sendACEMessage(user, Texts.of(TextColors.DARK_RED, "Page must be a number greater or equal to 1"));
                return false;
            }
            return true;
        }

        private void printEntries(Collection<?> entries, int page) {
            final int start = (page - 1) * ENTRIES_PER_PAGE;
            final int end = Math.min(start + ENTRIES_PER_PAGE, entries.size());
            if (end <= start) {
                sendACEMessage(user, Texts.of(TextColors.DARK_RED, "No entries for page ", page));
                return;
            }
            final Iterator<?> iterator = entries.iterator();
            for (int i = 0; i < end; i++) {
                final Object entry = iterator.next();
                if (i >= start) {
                    sendACEMessage(user, entryToText(entry));
                }
            }
        }

        private Text entryToText(Object entry) {
            if (entry instanceof Class) {
                return Texts.of(TextColors.DARK_GREEN, ((Class) entry).getCanonicalName());
            }
            if (entry instanceof Variable) {
                final Variable variable = (Variable) entry;
                final Text valueText = variable.initialized() ? Texts.of(TextColors.DARK_GREEN, " Value: ", TextColors.RESET, variable.getValue().asString()) : Texts.of();
                return Texts.of(
                        TextColors.DARK_GREEN, "Name: ", TextColors.RESET, variable.getName(),
                        TextColors.DARK_GREEN, " Type: ", TextColors.RESET, variable.getType().getName(),
                        valueText
                );
            }
            return Texts.of(entry);
        }

        private void resetEnvironment() {
            sendACEMessage(user, environment == null
                    ? Texts.of(TextColors.DARK_RED, "No active context")
                    : Texts.of(TextColors.DARK_GREEN, "Context deleted")
            );
            environment = null;
        }
    }

    @SuppressWarnings("unused")
    public static class Printer {
        private final CommandSource receiver;

        private Printer(CommandSource receiver) {
            this.receiver = receiver;
        }

        public void print(boolean message) {
            print(Boolean.toString(message));
        }

        public void print(byte message) {
            print(Byte.toString(message));
        }

        public void print(short message) {
            print(Short.toString(message));
        }

        public void print(char message) {
            print(Character.toString(message));
        }

        public void print(int message) {
            print(Integer.toString(message));
        }

        public void print(long message) {
            print(Long.toString(message));
        }

        public void print(float message) {
            print(Float.toString(message));
        }

        public void print(double message) {
            print(Double.toString(message));
        }

        public void print(Object message) {
            print(message.toString());
        }

        public void print(String message) {
            sendACEMessage(receiver, Texts.of(message));
        }
    }

    private static void sendACEMessage(CommandSource source, Text message) {
        source.sendMessage(Texts.of(TextColors.BLUE, "[ACE] ", TextColors.RESET, message));
    }
}

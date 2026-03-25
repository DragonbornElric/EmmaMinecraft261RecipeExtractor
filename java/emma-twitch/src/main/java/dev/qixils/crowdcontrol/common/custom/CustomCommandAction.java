package dev.qixils.crowdcontrol.common.custom;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public record CustomCommandAction(String type, @Nullable Map<String, Object> options) {
	@Contract("_, _, !null -> !null")
	@SuppressWarnings("unchecked")
	public <T> T getOption(String name, Class<T> clazz, @Nullable T def) {
		if (options == null) return def;

		try {
			Object value = options.get(name);
			if (value == null) return def;
			if (clazz.isInstance(value)) return (T) value;
			return def;
		} catch (Exception ignored) {
			return def;
		}
	}

	@Contract("_, !null -> !null")
	private String _getString(String name, @Nullable String def) {
		if (options == null) return def;

		try {
			Object value = options.get(name);
			if (value == null) return def;
			return value.toString();
		} catch (Exception ignored) {
			return def;
		}
	}

	@Contract("_, !null -> !null")
	public String getString(String name, @Nullable String def) {
		String value = _getString(name, def);
		if (value == null) return null;
		return value.trim();
	}

	public int getInt(String name, int def) {
		if (options == null) return def;

		try {
			Object value = options.get(name);
			if (value instanceof Number n) return n.intValue();
			return def;
		} catch (Exception ignored) {
			return def;
		}
	}
}

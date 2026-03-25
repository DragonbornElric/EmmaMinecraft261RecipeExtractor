package dev.qixils.crowdcontrol.common.custom;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public record CustomCommandData(
	Component name,
	int price,
	@Nullable String description,
	@Nullable String image,
	@Nullable List<@NotNull String> category,
	List<CustomCommandAction> actions
) {}

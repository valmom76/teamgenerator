package com.boraver.teamgenerator.dto.skill;

import jakarta.annotation.Nullable;

public record UpdateSkillRequest(@Nullable String name, @Nullable Boolean active) {}
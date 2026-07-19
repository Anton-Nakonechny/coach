package com.coach.web.dto;

import java.util.List;

/** A labelled group of practice topics (e.g. a CEFR level), the topics in workbook order. */
public record TopicSection(String level, List<String> topics) { }

package com.coach.web.dto;

import java.util.List;

/** Parsed multiple-choice question: a stem and exactly four options. */
public record QuizQuestion(String stem, List<QuizOption> options) { }

package com.alexastudillo.companyregistry.application.persistence;

import java.util.UUID;

/** Supplies generated identities without exposing randomness or native initialization to the core. */
public interface Identifiers { UUID next(); }

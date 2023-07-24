<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# SmallRye GraphQL Client IntelliJ Plugin Changelog

## [Unreleased]

## [1.0.0] - 2023-07-24

### Fixed
- Fixed the plugin metadata to allow running with IDEA 2023.2

## [0.2.0] - 2023-02-15

### Fixed
- In projects that have a `@GraphQLClientAPI` but no schema.graphql, the message that tells you about it was changed from an error to a notification

## [0.1.1] - 2023-01-24

### Added
- Autocompleting Query and Mutation methods in typesafe clients (interfaces annotated with `@GraphQLClient`)
- Autocompleting fields inside classes that are recognized as model classes of a typesafe GraphQL client 

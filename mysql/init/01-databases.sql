-- Runs once, on the first start of an empty mysql-data volume.
-- Each service owns exactly one database.
CREATE DATABASE IF NOT EXISTS quorum_placement  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS quorum_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS quorum_import     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS quorum_timetable  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS quorum_checkin    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

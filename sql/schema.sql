-- Run once against the Azure SQL database D6 will use (the same DB as D5 is fine).
IF OBJECT_ID('dbo.d6_entries', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.d6_entries (
        d5_id         INT           NOT NULL PRIMARY KEY,   -- = dbo.users.id in D5
        full_name     NVARCHAR(100) NOT NULL,
        email_address NVARCHAR(150) NOT NULL,
        city          NVARCHAR(100) NOT NULL,
        gender        NVARCHAR(50)  NOT NULL,
        status        NVARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING / REVIEWED
        received_at   DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
        reviewed_by   NVARCHAR(100) NULL,
        reviewed_at   DATETIME2     NULL
    );
END
GO

-- Let D6's App Service managed identity read/write (run as the Entra admin).
CREATE USER [<your-d6-app-service-name>] FROM EXTERNAL PROVIDER;
ALTER ROLE db_datareader ADD MEMBER [<your-d6-app-service-name>];
ALTER ROLE db_datawriter ADD MEMBER [<your-d6-app-service-name>];
GO

-- Token D6 uses to call D5's /api/* (only needed for Reject, which deletes in D5).
-- 1) make a long random token:  openssl rand -hex 32
-- 2) hash it:                   echo -n '<token>' | sha256sum
-- 3) insert the HASH below (adjust if AllowedSenders has other required columns);
--    the plain TOKEN goes into D6's D5_TOKEN app setting.
-- INSERT INTO dbo.AllowedSenders (Username, TokenHash) VALUES ('d6-review', '<sha256-hex-of-token>');

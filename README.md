# D6 – Review app for D5 entries

Dropdown of everyone saved by D5 -> pick a name -> details appear -> **Review** or **Reject**.
Reject calls `DELETE /api/users/{id}` on D5, so the entry disappears from D5's database.
Review is remembered in D6's own table `dbo.review_status`.

## Set up
1. Run `sql/schema.sql` on the Azure SQL database (fill in the D6 App Service name; add a D5 token, see comments in the file).
2. Create a NEW Azure App Service (Linux, Tomcat 10.1, Java 17) for D6 and turn on its system-assigned managed identity.
3. App Service > Environment variables:

| Setting | Value |
|---|---|
| DB_SERVER | same as D5 (abhishekarun.database.windows.net) |
| DB_NAME | same as D5 (free-sql-db-3779802) |
| D5_BASE_URL | D5 address, e.g. https://abhishekk.azurewebsites.net |
| D5_TOKEN | the plain token whose SHA-256 you stored in dbo.AllowedSenders |
| D6_USER / D6_PASSWORD | login for this app (the app stays locked until both are set) |

4. Put this folder in its own GitHub repo, point an Azure DevOps pipeline at it, and set `webAppName` in `azure-pipelines.yml`.

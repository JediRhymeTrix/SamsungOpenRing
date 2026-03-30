package io.github.thevellichor.samsungopenring.app;

interface IShizukuShellService {
    void destroy() = 16777114;
    int grantPermission(String packageName, String permission) = 1;
}

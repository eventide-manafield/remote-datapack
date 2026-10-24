package studio.manafield.remotedatapack.auth;

public final class AuthFailure extends RuntimeException {
    private final int status;
    public AuthFailure(int status, String code) { super(code, null, false, false); this.status = status; }
    public int status() { return status; }
}

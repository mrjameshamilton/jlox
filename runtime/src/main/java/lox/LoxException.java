package lox;

public class LoxException extends RuntimeException {

    public LoxException(String message) {
        super(message);
    }

    @Override
    public String getMessage() {
        int line = -1;
        // Skip over the "lox.**" internal runtime classes.
        for (int i = 0; i < getStackTrace().length; i++) {
            if (!getStackTrace()[i].getClassName().startsWith("lox.") && getStackTrace()[i].getLineNumber() != -1) {
                line = getStackTrace()[i].getLineNumber();
                break;
            }
        }
        return super.getMessage() + "\n[line " + line + "]";
    }
}

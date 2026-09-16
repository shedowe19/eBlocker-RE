package org.restexpress.domain;

public class ForwardedParseError
extends Exception
{
	private static final long serialVersionUID = -1605369715503248459L;

	public ForwardedParseError()
	{
		super();
	}

	public ForwardedParseError(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace)
	{
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public ForwardedParseError(String message, Throwable cause)
	{
		super(message, cause);
	}

	public ForwardedParseError(String message)
	{
		super(message);
	}

	public ForwardedParseError(Throwable cause)
	{
		super(cause);
	}
}

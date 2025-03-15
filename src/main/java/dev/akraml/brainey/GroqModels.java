package dev.akraml.brainey;

public enum GroqModels {
	
	LLAMA3_70B("llama3-70b-8192");
	
	private final String modelName;
	
	GroqModels(String modelName) {
		this.modelName = modelName;
	}
	
	public String getName() {
		return modelName;
	}
	
}
import os
import re
import requests
from bs4 import BeautifulSoup
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, END
from dotenv import load_dotenv
from datetime import datetime

# Load environment variables from .env file
load_dotenv()

# --- State Definition ---
class WorkflowState(dict):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.scraped_data = self.get('scraped_data', None)
        self.original_script = self.get('original_script', None)
        self.updated_script = self.get('updated_script', None)

# --- Node Functions ---

def scrape_confluence(state: WorkflowState) -> WorkflowState:
    """Scrapes the Confluence page to extract Program and Program Manager data."""
    print("---SCRAPING CONFLUENCE PAGE---")
    url = os.getenv("CONFLUENCE_URL")
    if not url:
        raise ValueError("CONFLUENCE_URL not found in .env file")

    # Note: This is a basic scraper. For pages requiring login, you'll need to handle authentication (e.g., with session cookies or an API token).
    try:
        response = requests.get(url)
        response.raise_for_status()  # Raise an exception for bad status codes
    except requests.exceptions.RequestException as e:
        print(f"Error fetching Confluence page: {e}")
        return {**state, "error": str(e)}

    soup = BeautifulSoup(response.content, 'html.parser')
    
    # This selector might need to be adjusted based on the actual HTML structure of your Confluence page.
    # You can use your browser's developer tools to inspect the table and find the correct selectors.
    table = soup.find('table') 
    if not table:
        print("No table found on the page.")
        return {**state, "error": "No table found"}

    headers = [header.text.strip() for header in table.find_all('th')]
    
    try:
        program_idx = headers.index("Program")
        manager_idx = headers.index("Program Manager")
    except ValueError:
        print("Could not find 'Program' or 'Program Manager' headers in the table.")
        return {**state, "error": "Required headers not found in table"}

    pm_data = {}
    for row in table.find_all('tr')[1:]:  # Skip header row
        cols = row.find_all('td')
        if len(cols) > max(program_idx, manager_idx):
            program = cols[program_idx].text.strip()
            manager = cols[manager_idx].text.strip()
            if program and manager:
                pm_data[program] = manager
    
    print(f"Scraped Data: {pm_data}")
    return {**state, "scraped_data": pm_data}

def update_groovy_script(state: WorkflowState) -> WorkflowState:
    """Updates the Groovy script with the new Program Manager data using an LLM."""
    print("---UPDATING GROOVY SCRIPT---")
    pm_data = state.get('scraped_data')
    if not pm_data:
        print("No scraped data to process.")
        return state

    # Read the original Groovy script
    try:
        with open("../Script/CIPR/PM.groovy", "r", encoding="utf-8") as f:
            original_script = f.read()
    except FileNotFoundError:
        return {**state, "error": "Original Groovy script not found."}

    # Prepare the prompt for the LLM
    prompt = f"""
    You are a helpful assistant that updates Groovy scripts.
    Given the following new Program Manager data:
    {pm_data}

    Update the `mappingTable` in the following Groovy script.
    - Replace the existing key-value pairs with the new data.
    - Keep the existing format and comments.
    - Do not change any other part of the script.

    Original Script:
    ```groovy
    {original_script}
    ```
    """

    # Call the LLM
    llm = ChatOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    response = llm.invoke(prompt)
    updated_script = response.content

    # Extract only the groovy script from the response
    match = re.search(r'```groovy\n(.*?)\n```', updated_script, re.DOTALL)
    if match:
        updated_script = match.group(1)

    print("---UPDATED SCRIPT---")
    print(updated_script)
    
    return {**state, "original_script": original_script, "updated_script": updated_script}


def save_new_script(state: WorkflowState) -> WorkflowState:
    """Saves the
updated script to a new file with a timestamp."""
    print("---SAVING NEW SCRIPT---")
    updated_script = state.get('updated_script')
    if not updated_script:
        print("No updated script to save.")
        return state

    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    new_filename = f"PM_{timestamp}.groovy"
    
    try:
        with open(new_filename, "w", encoding="utf-8") as f:
            f.write(updated_script)
        print(f"Successfully saved updated script to {new_filename}")
    except IOError as e:
        print(f"Error saving new script: {e}")
        return {**state, "error": str(e)}
        
    return state

# --- Graph Definition ---

def build_graph():
    """Builds the langgraph workflow."""
    workflow = StateGraph(WorkflowState)

    workflow.add_node("scrape_confluence", scrape_confluence)
    workflow.add_node("update_groovy_script", update_groovy_script)
    workflow.add_node("save_new_script", save_new_script)

    workflow.set_entry_point("scrape_confluence")
    workflow.add_edge("scrape_confluence", "update_groovy_script")
    workflow.add_edge("update_groovy_script", "save_new_script")
    workflow.add_edge("save_new_script", END)

    return workflow.compile()

# --- Main Execution ---

if __name__ == "__main__":
    # Create a .env file in the autoPM directory with your Confluence URL and OpenAI API key
    # Example .env file:
    # CONFLUENCE_URL="https://your-confluence-instance.com/..."
    # OPENAI_API_KEY="your-api-key"

    if not os.path.exists(".env"):
        print("Error: .env file not found. Please create one with your CONFLUENCE_URL and OPENAI_API_KEY.")
    else:
        app = build_graph()
        initial_state = WorkflowState()
        app.invoke(initial_state)